package fdu.secsys.microservice.plugin;

import fdu.secsys.microservice.Config;
import fdu.secsys.microservice.enums.QueryType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.w3c.dom.*;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import pascal.taie.World;
import pascal.taie.analysis.pta.core.cs.context.Context;
import pascal.taie.analysis.pta.core.cs.element.CSMethod;
import pascal.taie.analysis.pta.core.cs.element.CSVar;
import pascal.taie.analysis.pta.core.cs.element.InstanceField;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.analysis.pta.core.solver.Solver;
import pascal.taie.analysis.pta.plugin.Plugin;
import pascal.taie.analysis.pta.plugin.taint.EnhanceHandlerContext;
import pascal.taie.analysis.pta.plugin.taint.EnhanceSink;
import pascal.taie.analysis.pta.plugin.taint.EnhanceTaintManager;
import pascal.taie.analysis.pta.plugin.taint.SourcePoint;
import pascal.taie.analysis.pta.pts.PointsToSet;
import pascal.taie.ir.exp.InvokeExp;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.ir.stmt.StmtVisitor;
import pascal.taie.language.annotation.Annotation;
import pascal.taie.language.annotation.StringElement;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.type.ClassType;
import pascal.taie.util.collection.Maps;
import pascal.taie.util.collection.MultiMap;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class MybatisXmlPlugin implements Plugin {

    static Logger logger = LogManager.getLogger(MybatisXmlPlugin.class);

    final static String VUL_ID = "SQLI_Mybatis_Xml";

    DocumentBuilderFactory dbFactory;

    static final List<String> OperationTypes = Arrays.asList("select", "insert", "update", "delete");

    private final List<EnhanceSink> enhanceSinks;

    private final Map<String, String> operationTypeMap = new HashMap<>();

    private final MultiMap<Var, Var> selectArgResultMap = Maps.newMultiMap();

    private final Solver solver;

    private final EnhanceTaintManager manager;

    public MybatisXmlPlugin(EnhanceHandlerContext context) {
        this.enhanceSinks = context.config().sinks();
        this.solver = context.solver();
        this.manager = context.manager();
    }

    public QueryType getOperationType(String operation) {
        String type = operationTypeMap.get(operation);
        if (type == null) return QueryType.UNKNOWN;
        return switch (type) {
            case "select" -> QueryType.SELECT;
            case "insert" -> QueryType.INSERT;
            case "update" -> QueryType.UPDATE;
            case "delete" -> QueryType.DELETE;
            default -> QueryType.UNKNOWN;
        };
    }

    @Override
    public void onStart() {
        Path targetPath = Path.of(Config.targetPath);
        Path mapperPath = targetPath.resolve("mapper");
        if (!mapperPath.toFile().isDirectory()) {
            logger.debug(mapperPath + " is not a directory");
            return;
        }

        Map<JMethod, List<Integer>> injectMaps = new HashMap<>();

        dbFactory = DocumentBuilderFactory.newInstance();
        dbFactory.setNamespaceAware(true);
        try {
            dbFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        } catch (Exception e) {
            logger.warn(e);
        }

        ExecutorService executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        try (Stream<Path> paths = Files.walk(mapperPath)) {
            paths.filter(path -> path.getFileName().toString().endsWith(".xml")).forEach(path -> submitFileProcessing(executorService, path, injectMaps));
        } catch (IOException e) {
            logger.error("Failed to read directory: " + mapperPath, e);
        } finally {
            awaitTermination(executorService);
        }

        injectMaps.forEach((jMethod, integers) -> {
            integers.forEach(pos -> {
                EnhanceSink enhanceSink = new EnhanceSink(jMethod.getSignature(), pos, null, null, VUL_ID);
                enhanceSinks.add(enhanceSink);
                logger.info("add dynamic {} sink: {}", VUL_ID, enhanceSink);
            });
        });

    }

    @Override
    public void onNewPointsToSet(CSVar csVar, PointsToSet pts) {
        Var var = csVar.getVar();
        Set<Var> resultVars = selectArgResultMap.get(var);
        Set<Obj> taintObjs = new HashSet<>();
        if (!resultVars.isEmpty()) {
            pts.forEach(csObj -> {
                Obj obj = csObj.getObject();
                if (manager.isTaint(obj))
                    taintObjs.add(obj);
                if (obj.getType() instanceof ClassType classType) {
                    JClass jClass = classType.getJClass();
                    if (jClass != null && jClass.isApplication()) {
                        jClass.getDeclaredFields().forEach(jField -> {
                            InstanceField instanceField = solver.getCSManager().getInstanceField(csObj, jField);
                            solver.getPointsToSetOf(instanceField).forEach(csFieldObj -> {
                                Obj fieldObj = csFieldObj.getObject();
                                if (manager.isTaint(fieldObj))
                                    taintObjs.add(fieldObj);
                            });
                        });
                    }
                }
            });
            resultVars.forEach(resultVar -> {
                taintObjs.forEach(taintObj -> {
                    CSVar csResultVar = solver.getCSManager().getCSVar(csVar.getContext(), resultVar);
                    Obj newTaintObj = manager.makeTaint((SourcePoint) taintObj.getAllocation(), csResultVar.getType(), true);
                    solver.addPointsTo(csResultVar, newTaintObj);
                });
            });
        }
    }

    @Override
    public void onNewCSMethod(CSMethod csMethod) {
        Visitor visitor = new Visitor(csMethod);
        csMethod.getMethod().getIR().getStmts().forEach(stmt -> stmt.accept(visitor));
    }

    private class Visitor implements StmtVisitor<Void> {
        private final CSMethod csMethod;

        private final Context context;

        private Visitor(CSMethod csMethod) {
            this.csMethod = csMethod;
            this.context = csMethod.getContext();
        }

        @Override
        public Void visit(Invoke stmt) {
            if (!stmt.isDynamic()) {
                InvokeExp invokeExp = stmt.getInvokeExp();
                JMethod jMethod = invokeExp.getMethodRef().resolveNullable();
                if (jMethod == null) return null;
                String operation = "%s.%s".formatted(jMethod.getDeclaringClass().getName(), jMethod.getName());
                if (QueryType.SELECT.equals(getOperationType(operation)) && stmt.getDef().isPresent()) {
                    for (int i = 0; i < invokeExp.getArgCount(); i++) {
                        selectArgResultMap.put(invokeExp.getArg(i), stmt.getResult());
                    }
                }
            }
            return null;
        }
    }

    private Map<JMethod, List<Integer>> processXMLMapper(InputStream stream) throws ParserConfigurationException, IOException, SAXException {
        Map<JMethod, List<Integer>> injectMap = new HashMap<>();
        DocumentBuilder builder = dbFactory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) -> {
            if (publicId != null && publicId.equals("-//mybatis.org//DTD Mapper 3.0//EN")) {
                return new InputSource(ClassLoader.getSystemResourceAsStream("mybatis-3-mapper.dtd"));
            }
            return null;
        });
        Document doc = builder.parse(stream);
        String className;
        NodeList mapperNodes = doc.getElementsByTagName("mapper");
        if (mapperNodes.getLength() > 0) {
            Element mapperElement = (Element) mapperNodes.item(0);
            className = mapperElement.getAttribute("namespace");
            logger.debug("Mapper: {}", className);

            OperationTypes.forEach(operationType -> {
                        NodeList nodes = doc.getElementsByTagName(operationType);
                        for (int i = 0; i < nodes.getLength(); i++) {
                            try {
                                Element sqlElement = (Element) nodes.item(i);
                                String id = sqlElement.getAttribute("id");
                                String operation = "%s.%s".formatted(className, id);
                                operationTypeMap.put(operation, operationType);
                                StringBuilder sql = new StringBuilder();
                                processElement(mapperElement, sqlElement, sql);
                                String sqlString = sql.toString();
                                Pattern pattern = Pattern.compile("(\\$\\{[\\w\\.\\s]+\\})");
                                Matcher matcher = pattern.matcher(sqlString);
                                Set<String> injects = matcher.results().map(matchResult -> {
                                    String group = matchResult.group();
                                    if (group.startsWith("${")) {
                                        group = group.substring(2);
                                    }
                                    if (group.endsWith("}")) {
                                        group = group.substring(0, group.length() - 1);
                                    }
                                    int index = group.indexOf('.');
                                    if (index != -1) {
                                        group = group.substring(0, index);
                                    }
                                    return group;
                                }).collect(Collectors.toSet());
                                if (!injects.isEmpty()) {
                                    JClass jClass = World.get().getClassHierarchy().getClass(className);
                                    if (jClass == null) continue;
                                    String methodName = sqlElement.getAttribute("id");
                                    JMethod jMethod = jClass.getDeclaredMethod(methodName);
                                    if (jMethod == null) continue;
                                    List<Integer> injectPos = new ArrayList<>();
                                    if (jMethod.getParamCount() == 1) {
                                        boolean injectAble = true;
                                        NodeList properties = sqlElement.getElementsByTagName("property");
                                        for (int j = 0; j < properties.getLength(); j++) {
                                            try {
                                                Element propertyElement = (Element) properties.item(j);
                                                String name = propertyElement.getAttribute("name");
                                                String value = propertyElement.getAttribute("value");
                                                if (injects.stream().anyMatch(injectName -> injectName.contains(name)) && !value.contains("$")) {
                                                    injectAble = false;
                                                }
                                            } catch (Exception e) {
                                                logger.warn(e);
                                            }
                                        }
                                        if (injectAble) injectPos.add(0);
                                    } else {
                                        for (int pos = 0; pos < jMethod.getParamCount(); pos++) {
                                            int finalPos = pos;
                                            Collection<Annotation> paramAnnotations = jMethod.getParamAnnotations(pos);
                                            if (!paramAnnotations.isEmpty()) {
                                                paramAnnotations.forEach(annotation -> {
                                                    if (annotation.getType().endsWith(".apache.ibatis.annotations.Param")) {
                                                        try {
                                                            StringElement stringElement = (StringElement) annotation.getElement("value");
                                                            String param = stringElement.value();
                                                            if (injects.contains(param)) {
                                                                injectPos.add(finalPos);
                                                            }
                                                        } catch (Exception e) {
                                                            logger.warn(e);
                                                        }
                                                    }
                                                });
                                            } else {
                                                String paramName = jMethod.getParamName(pos);
                                                if (injects.contains(paramName)) {
                                                    injectPos.add(pos);
                                                } else if (injects.stream().anyMatch(inject -> inject.toLowerCase().contains("query")) && jMethod.getParamType(pos).getName().toLowerCase().contains("query")) {
                                                    injectPos.add(pos);
                                                }
                                            }
                                        }
                                    }
                                    if (!injectPos.isEmpty()) {
                                        injectMap.put(jMethod, injectPos);
                                    }
                                }
                            } catch (Exception e) {
                                logger.warn(e);
                            }
                        }
                    }
            );
        }
        return injectMap;
    }

    private void submitFileProcessing(ExecutorService executor, Path path, Map<JMethod, List<Integer>> injectMaps) {
        executor.submit(() -> {
            try (FileInputStream inputStream = new FileInputStream(path.toFile())) {
                Map<JMethod, List<Integer>> injectMap = processXMLMapper(inputStream);
                synchronized (injectMaps) {
                    logger.debug("Found " + injectMap.size() + " db operations in " + path.toFile());
                    injectMaps.putAll(injectMap);
                }
            } catch (IOException | ParserConfigurationException | SAXException e) {
                logger.error("Error processing file: " + path, e);
            }
        });
    }

    private void awaitTermination(ExecutorService executor) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException ie) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public void processElement(Element doc, Element element, StringBuilder sql) {
        if (element.getTagName().equalsIgnoreCase("include")) {
            String refid = element.getAttribute("refid");
            if (!refid.trim().isEmpty()) {
                Element refElement = findElementById(doc, refid);
                if (refElement != null) {
                    processElement(doc, refElement, sql);
                }
            }
        } else if (element.getTagName().equalsIgnoreCase("if") ||
                element.getTagName().equalsIgnoreCase("when") ||
                element.getTagName().equalsIgnoreCase("sql") ||
                element.getTagName().equalsIgnoreCase("foreach") ||
                OperationTypes.contains(element.getTagName().toLowerCase())
        ) {
            processChildren(doc, element, sql);
        } else if (element.getTagName().equalsIgnoreCase("choose")) {
            processChildren(doc, element, sql);
        } else if (element.getTagName().equalsIgnoreCase("trim")) {
            sql.append(element.getAttribute("prefix"));
            processChildren(doc, element, sql);
            sql.append(element.getAttribute("suffix"));
        } else {
            sql.append(element.getTagName()).append(" ");
            processChildren(doc, element, sql);
        }
    }

    private void processChildren(Element doc, Element element, StringBuilder sql) {
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            processChild(doc, child, sql);
        }
    }

    private void processChild(Element doc, Node child, StringBuilder sql) {
        if (child instanceof Text) {
            String textContent = child.getNodeValue();
            if (textContent != null && !textContent.trim().isEmpty()) {
                sql.append(textContent).append(" ");
            }
        } else if (child instanceof Element) {
            processElement(doc, (Element) child, sql);
        }
    }

    private Element findElementById(Element element, String id) {
        if (id.equals(element.getAttribute("id"))) {
            return element;
        }
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element) {
                Element result = findElementById((Element) child, id);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

}

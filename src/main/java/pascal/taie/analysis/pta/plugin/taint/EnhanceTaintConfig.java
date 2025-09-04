/*
 * Tai-e: A Static Analysis Framework for Java
 *
 * Copyright (C) 2022 Tian Tan <tiantan@nju.edu.cn>
 * Copyright (C) 2022 Yue Li <yueli@nju.edu.cn>
 *
 * This file is part of Tai-e.
 *
 * Tai-e is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License
 * as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * Tai-e is distributed in the hope that it will be useful,but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General
 * Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with Tai-e. If not, see <https://www.gnu.org/licenses/>.
 */

package pascal.taie.analysis.pta.plugin.taint;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.ObjectCodec;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pascal.taie.analysis.pta.plugin.util.InvokeUtils;
import pascal.taie.config.ConfigException;
import pascal.taie.language.classes.ClassHierarchy;
import pascal.taie.language.type.TypeSystem;
import pascal.taie.util.collection.Lists;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Configuration for taint analysis.
 */
public record EnhanceTaintConfig(List<EnhanceSink> sinks, boolean callSiteMode) {

    private static final Logger logger = LogManager.getLogger(EnhanceTaintConfig.class);

    /**
     * An empty taint config.
     */
    private static final EnhanceTaintConfig EMPTY = new EnhanceTaintConfig(new ArrayList<>(), false);

    /**
     * Loads a taint analysis configuration from given path.
     * If the path is a file, then loads config from the file;
     * if the path is a directory, then loads all YAML files in the directory
     * and merge them as the result.
     *
     * @param path       the path
     * @param hierarchy  the class hierarchy
     * @param typeSystem the type manager
     * @return the resulting {@link EnhanceTaintConfig}
     * @throws ConfigException if failed to load the config
     */
    public static EnhanceTaintConfig loadConfig(
            String path, ClassHierarchy hierarchy, TypeSystem typeSystem) {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        SimpleModule module = new SimpleModule();
        module.addDeserializer(EnhanceTaintConfig.class,
                new Deserializer(hierarchy, typeSystem));
        mapper.registerModule(module);
        File file = new File(path);
        logger.info("Loading enhance taint config from {}", file.getAbsolutePath());
        if (file.isFile()) {
            return loadSingle(mapper, file);
        } else if (file.isDirectory()) {
            // if file is a directory, then load all YAML files
            // in the directory and merge them as the result
            EnhanceTaintConfig[] result = new EnhanceTaintConfig[]{EMPTY};
            try (Stream<Path> paths = Files.walk(file.toPath())) {
                paths.filter(EnhanceTaintConfig::isYAML)
                        .map(p -> loadSingle(mapper, p.toFile()))
                        .forEach(tc -> result[0] = result[0].mergeWith(tc));
                return result[0];
            } catch (IOException e) {
                throw new ConfigException("Failed to load enhance taint config from " + file, e);
            }
        } else {
            throw new ConfigException(path + " is neither a file nor a directory");
        }
    }

    /**
     * Loads taint config from a single file.
     */
    private static EnhanceTaintConfig loadSingle(ObjectMapper mapper, File file) {
        try {
            return mapper.readValue(file, EnhanceTaintConfig.class);
        } catch (IOException e) {
            throw new ConfigException("Failed to load enhance taint config from " + file, e);
        }
    }

    private static boolean isYAML(Path path) {
        String pathStr = path.toString();
        return pathStr.endsWith(".yml") || pathStr.endsWith(".yaml");
    }

    /**
     * Merges this taint config with other taint config.
     *
     * @return a new merged taint config.
     */
    EnhanceTaintConfig mergeWith(EnhanceTaintConfig other) {
        return new EnhanceTaintConfig(
                Lists.concatDistinct(sinks, other.sinks),
                callSiteMode || other.callSiteMode);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("EnhanceTaintConfig:");
        if (!sinks.isEmpty()) {
            sb.append("\nenhance_sinks:\n");
            sinks.forEach(sink ->
                    sb.append("  ").append(sink).append("\n"));
        }
        return sb.toString();
    }

    /**
     * Deserializer for {@link EnhanceTaintConfig}.
     */
    private static class Deserializer extends JsonDeserializer<EnhanceTaintConfig> {

        private final ClassHierarchy hierarchy;

        private final TypeSystem typeSystem;

        private Deserializer(ClassHierarchy hierarchy, TypeSystem typeSystem) {
            this.hierarchy = hierarchy;
            this.typeSystem = typeSystem;
        }

        @Override
        public EnhanceTaintConfig deserialize(JsonParser p, DeserializationContext ctxt)
                throws IOException {
            ObjectCodec oc = p.getCodec();
            JsonNode node = oc.readTree(p);
            List<EnhanceSink> sinks = deserializeSinks(node.get("enhance_sinks"));
            JsonNode callSiteNode = node.get("call-site-mode");
            boolean callSiteMode = (callSiteNode != null && callSiteNode.asBoolean());
            return new EnhanceTaintConfig(sinks, callSiteMode);
        }

        /**
         * Deserializes a {@link JsonNode} (assume it is an {@link ArrayNode})
         * to a list of {@link Sink}.
         *
         * @param node the node to be deserialized
         * @return list of deserialized {@link Sink}
         */
        private List<EnhanceSink> deserializeSinks(JsonNode node) {
            if (node instanceof ArrayNode arrayNode) {
                List<EnhanceSink> sinks = new ArrayList<>(arrayNode.size());
                for (JsonNode elem : arrayNode) {
                    String methodSig = elem.get("method").asText();
                    int index = InvokeUtils.toInt(elem.get("index").asText());
                    String vulId = elem.has("vul_id") ? elem.get("vul_id").asText() : "";
                    sinks.add(new EnhanceSink(methodSig, index, null, null, vulId));
                }
                return sinks;
            } else {
                // if node is not an instance of ArrayNode, just return an empty set.
                return new ArrayList<>();
            }
        }
    }
}

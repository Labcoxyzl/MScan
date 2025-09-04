package fdu.secsys.microservice.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pascal.taie.World;
import pascal.taie.language.annotation.ClassElement;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JField;
import pascal.taie.language.type.ClassType;
import pascal.taie.util.collection.Maps;
import pascal.taie.util.collection.MultiMap;
import pascal.taie.util.collection.Sets;

import java.util.Map;
import java.util.Set;

public class DubboUtil {

    static Logger logger = LogManager.getLogger(DubboUtil.class);

    public static boolean isDubboService(JClass jClass) {
        return jClass.hasAnnotation("org.apache.dubbo.config.annotation.DubboService")
                || jClass.hasAnnotation("com.alibaba.dubbo.config.annotation.Service")
                || jClass.hasAnnotation("org.apache.dubbo.config.annotation.Service");
    }

}

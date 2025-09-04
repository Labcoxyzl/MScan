package fdu.secsys.microservice.util;

import pascal.taie.language.annotation.Annotated;
import pascal.taie.language.annotation.Annotation;
import pascal.taie.language.annotation.ArrayElement;
import pascal.taie.language.annotation.StringElement;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JMethod;

import java.util.ArrayList;
import java.util.List;

public class KafkaUtil {
    public static boolean isKafkaListener(JMethod jMethod) {
       return jMethod.hasAnnotation("org.springframework.kafka.annotation.KafkaListener");
    }

    public static String getListenerTopic(JMethod jMethod) {
        if(!isKafkaListener(jMethod)) return "";
        Annotation annotation = jMethod.getAnnotation("org.springframework.kafka.annotation.KafkaListener");
        if(annotation.getElement("topics") instanceof ArrayElement topics) {
            List<String> topicsList = topics.elements().stream().filter(element -> element instanceof StringElement).map(element -> ((StringElement) element).value()).toList();
            if(topicsList.size() > 0) {
                return topicsList.get(0);
            }
        }
        return "";
    }
}

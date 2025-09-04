package fdu.secsys.microservice.util;

import pascal.taie.language.annotation.Annotation;
import pascal.taie.language.annotation.ArrayElement;
import pascal.taie.language.annotation.StringElement;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JMethod;

import java.util.List;

public class RabbitMQUtil {
    public static boolean isRabbitMQListener(JMethod jMethod) {
        return jMethod.hasAnnotation("org.springframework.amqp.rabbit.annotation.RabbitListener");
    }

    public static String getListenerQueue(JMethod jMethod) {
        if(!isRabbitMQListener(jMethod)) return "";
        Annotation annotation = jMethod.getAnnotation("org.springframework.amqp.rabbit.annotation.RabbitListener");
        if(annotation.getElement("queues") instanceof ArrayElement queues) {
            List<String> queuesList = queues.elements().stream().filter(element -> element instanceof StringElement).map(element -> ((StringElement) element).value()).toList();
            if(queuesList.size() > 0) {
                return queuesList.get(0);
            }
        }
        return "";
    }
}

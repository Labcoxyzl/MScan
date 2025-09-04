package fdu.secsys.microservice.util;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class YamlUtil {

    private List<Object> documents;
    private Object temp;

    public YamlUtil(InputStream fileInputStream) {
        this.loadAll(fileInputStream);
    }

    private void loadAll(InputStream fileInputStream) {
        Yaml yaml = new Yaml();
        this.documents = new ArrayList<>();
        Iterable<Object> allDocs = yaml.loadAll(fileInputStream);
        allDocs.forEach(documents::add);
        if (!this.documents.isEmpty()) {
            this.temp = this.documents.get(0);
        }
    }

    public YamlUtil prefix(String prefix) {
        if (prefix == null || prefix.trim().isEmpty()) {
            return this;
        }
        String[] keys = prefix.trim().split("\\.");
        for (Object doc : this.documents) {
            temp = filterByPrefix(doc, keys, 0);
            if (temp != null) break;
        }
        return this;
    }

    private Object filterByPrefix(Object current, String[] keys, int index) {
        if (index >= keys.length || current == null) {
            return current;
        }
        if (current instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) current;
            return filterByPrefix(map.get(keys[index]), keys, index + 1);
        } else if (current instanceof List && isNumeric(keys[index])) {
            List<?> list = (List<?>) current;
            int idx = Integer.parseInt(keys[index]);
            return idx < list.size() ? filterByPrefix(list.get(idx), keys, index + 1) : null;
        }
        return null;
    }

    public static boolean isNumeric(String str) {
        return str != null && str.matches("\\d+");
    }

    public Object getObj() {
        return this.temp;
    }

    public Map getMap() {
        if (this.temp instanceof Map) {
            return (Map) this.temp;
        }
        return null;
    }

    public List getList() {
        if (this.temp instanceof List) {
            return (List) this.temp;
        }
        return null;
    }

    public String getString() {
        return this.temp == null ? "" : this.temp.toString();
    }
}

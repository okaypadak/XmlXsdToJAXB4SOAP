package XmlToJAXB.component;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import XmlToJAXB.exception.ProcessingException;
import XmlToJAXB.service.IConverter;

@Service
public class JsonGenerate implements IConverter {

    private Map<String, List<ElementInfo>> classMap = new LinkedHashMap<>();

    @Override
    public void convert(File file, String fullOutputDir) throws ProcessingException {
        try {
            classMap.clear();
            parseJson(file);
            generateJavaClasses(fullOutputDir, file.getName());
        } catch (Exception e) {
            throw new ProcessingException("Bu JSON sorunludur: " + e.getMessage());
        }
    }

    private static class ElementInfo {
        String name;
        String type;
        boolean isClass;
        boolean isList;
        String parentName;

        ElementInfo(String name, String type, boolean isClass, boolean isList, String parentName) {
            this.name = name;
            this.type = type;
            this.isClass = isClass;
            this.isList = isList;
            this.parentName = parentName;
        }
    }

    private void parseJson(File file) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode rootNode = objectMapper.readTree(file);
        String rootClassName = toClassName(file.getName().replace(".json", ""));
        extractElements(rootNode, rootClassName, null);
    }

    private void extractElements(JsonNode node, String parentName, String grandParent) {
        if (node.isObject()) {
            String key = generateUniqueKey(parentName, grandParent);
            List<ElementInfo> fields = classMap.computeIfAbsent(key, k -> new ArrayList<>());

            node.fieldNames().forEachRemaining(fieldName -> {
                JsonNode childNode = node.get(fieldName);
                boolean isClass = childNode.isObject();
                boolean isList = childNode.isArray();
                String type = determineType(fieldName, childNode);
                String className = toClassName(fieldName);
                String childKey = generateUniqueKey(className, parentName);

                if (isList && !childNode.isEmpty()) {
                    extractElements(childNode.get(0), className, parentName);
                } else if (isClass) {
                    extractElements(childNode, className, parentName);
                }

                fields.add(new ElementInfo(fieldName, type, isClass, isList, parentName));

                if (isList && !classMap.containsKey(childKey)) {
                    classMap.put(childKey, new ArrayList<>());
                }
            });
        } else if (node.isArray() && !node.isEmpty()) {
            extractElements(node.get(0), parentName, grandParent);
        }
    }

    private String generateUniqueKey(String className, String parentName) {
        return (parentName == null) ? className : parentName + "_" + className;
    }

    private String determineType(String fieldName, JsonNode node) {
        if (node.isObject()) {
            return toClassName(fieldName);
        }
        if (node.isArray() && !node.isEmpty()) {
            JsonNode firstElement = node.get(0);
            if (firstElement.isObject()) {
                return "List<" + toClassName(fieldName) + ">";
            } else {
                return "List<" + determineType(fieldName, firstElement) + ">";
            }
        }
        if (node.isTextual()) return "String";
        if (node.isInt()) return "Integer";
        if (node.isBigDecimal() || node.isDouble()) return "BigDecimal";
        if (node.isBoolean()) return "Boolean";
        return "Object";
    }

    private void generateJavaClasses(String outputPath, String fileName) throws Exception {
        File file = new File(outputPath, fileName.replace(".json", ".java"));
        if (!file.exists()) file.createNewFile();

        try (FileWriter writer = new FileWriter(file)) {
            writer.write("package com.generated;\n\n");
            writer.write("import java.util.List;\n");
            writer.write("import lombok.Getter;\nimport lombok.Setter;\n\n");
            String rootClassName = toClassName(fileName.replace(".json", ""));
            generateNestedClass(writer, rootClassName, null, true);
        }
    }

    private void generateNestedClass(FileWriter writer, String className, String parentName, boolean isFirstClass) throws Exception {
        String key = generateUniqueKey(className, parentName);
        if (!classMap.containsKey(key)) return;

        writer.write("@Getter\n@Setter\n");
        writer.write((isFirstClass ? "public " : "public static ") + "class " + className + " {\n");

        for (ElementInfo field : classMap.get(key)) {
            writer.write("    private " + field.type + " " + field.name + ";\n");
        }

        for (ElementInfo field : classMap.get(key)) {
            if (field.isClass || field.isList) {
                generateNestedClass(writer, toClassName(field.name), className, false);
            }
        }

        writer.write("}\n\n");
    }

    private static String toClassName(String name) {
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
package analyzer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * 解析源码工程里的 pom.xml，取回声明的依赖清单。
 *
 * <p>与 {@link DependencyScanner} 分工明确：那边读的是**构建产物**（jar 里打包进去的元数据），
 * 这边读的是**源码工程**（声明要引入什么）。两者结论不一致本身就是有用信息——
 * 依赖被 exclusions 排除、profile 未激活、或 jar 被裁剪过，都会在这里体现出来。
 *
 * <p>用 JDK 自带的 DOM 而非字符串正则：pom.xml 里 {@code <dependencyManagement>}、
 * {@code <profiles>}、注释里都可能有依赖片段，正则取到的是「出现过的依赖」，
 * 而这里要的是「生效的依赖」。命名空间也不固定，因此统一按 localName 比对。
 */
public final class PomScanner {

    /** 生成的依赖来源标记：pom 声明，可信度与 pom.properties 同级。 */
    private static final Dependency.Source SOURCE = Dependency.Source.POM_XML;

    private PomScanner() {
    }

    /**
     * 解析一个 pom.xml，返回直接依赖清单。
     *
     * @param pom pom.xml 路径；不存在或非法时返回空清单而不是抛异常
     */
    public static List<Dependency> dependencies(Path pom) {
        if (pom == null || !Files.isRegularFile(pom)) return Collections.emptyList();
        Document document;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            // 外部实体一律不解析：pom.xml 可能来自不可信来源（第三方交付的源码包），
            // 放开 XXE 等于让对方读本机文件
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            document = builder.parse(pom.toFile());
        } catch (Exception unreadable) {
            return Collections.emptyList();
        }

        Element project = document.getDocumentElement();
        if (project == null) return Collections.emptyList();
        List<Dependency> found = new ArrayList<Dependency>();
        // 只取顶层 <dependencies>：<dependencyManagement> 是版本约束不是实际引入，
        // <profiles> 下的依赖是否生效取决于激活条件，都不算「已声明引入」
        Element dependencies = child(project, "dependencies");
        if (dependencies == null) return Collections.emptyList();
        for (Element node : children(dependencies, "dependency")) {
            String group = text(node, "groupId");
            String artifact = text(node, "artifactId");
            String version = text(node, "version");
            String scope = text(node, "scope");
            if (artifact.isEmpty()) continue;
            String evidence = pom.getFileName() + " <dependencies>"
                    + (scope.isEmpty() ? "" : " scope=" + scope);
            found.add(new Dependency(group, artifact, version, SOURCE, evidence));
        }
        return Collections.unmodifiableList(found);
    }

    /** 读取 pom.xml 文本里的 artifactId（不含父级与 dependencyManagement）。 */
    public static String projectName(Path pom) {
        if (pom == null || !Files.isRegularFile(pom)) return "";
        try {
            byte[] raw = Files.readAllBytes(pom);
            String text = new String(raw, StandardCharsets.UTF_8);
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            Document document = factory.newDocumentBuilder()
                    .parse(new java.io.ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
            return text(document.getDocumentElement(), "artifactId");
        } catch (Exception unreadable) {
            return "";
        }
    }

    /** 取直接子元素里 localName 匹配的第一个。 */
    private static Element child(Element parent, String localName) {
        for (Element element : children(parent, localName)) return element;
        return null;
    }

    private static List<Element> children(Element parent, String localName) {
        List<Element> matched = new ArrayList<Element>();
        if (parent == null) return matched;
        NodeList nodes = parent.getChildNodes();
        for (int index = 0; index < nodes.getLength(); index++) {
            Node node = nodes.item(index);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;
            Element element = (Element) node;
            String name = element.getLocalName() == null ? element.getNodeName() : element.getLocalName();
            if (localName.equals(name)) matched.add(element);
        }
        return matched;
    }

    private static String text(Element parent, String localName) {
        Element element = child(parent, localName);
        if (element == null) return "";
        String value = element.getTextContent();
        return value == null ? "" : value.trim();
    }
}

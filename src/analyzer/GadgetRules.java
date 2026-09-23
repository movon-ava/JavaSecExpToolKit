package analyzer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 内置 gadget 规则表：把「依赖组合」映射到「哪些链具备」。
 *
 * <p>规则是**我们自己写的**，不是从上游工具拷来的：{@code jar-analyzer} 是 GPLv3，
 * 把它的 {@code gadget.dat} 搬进本仓库会有许可问题。规则内容取自各组件公开的
 * gadget 形态与修复公告（CommonsCollections 的 InvokerTransformer 在 3.2.2 被打补丁、
 * CommonsCollections4 的 PriorityQueue 入口、Beanutils 的 BeanComparator、
 * c3p0 的反射工厂、BCEL 的 ClassLoader、Groovy / Jython 的字节码执行入口等）。
 *
 * <p>收录标准沿用仓库既有约定：**判定出来必须能接上后续动作**。
 * 一条链在报告里被判为「具备」，使用者就该能拿着它去「Payload 生成 / 预设链 /
 * toString 链」把载荷构出来；接不上任何动作的组件不进这张表。
 *
 * <p>与 {@link VulnerabilityRules} 的分工：规则表回答「这个组件本身有没有已知漏洞」，
 * 本表回答「这些依赖凑在一起，哪几条链能构建出来」——CommonsCollections 自身不算漏洞，
 * 它是「反序列化入口存在时能达成 RCE」的能力。两张表不重复收录同一条结论。
 */
public final class GadgetRules {

    private static final List<GadgetRule> RULES = Collections.unmodifiableList(Arrays.asList(

            // ------------------------------------------------------------ 原生反序列化
            GadgetRule.of("GAD-CC3", GadgetRule.Category.NATIVE, "CommonsCollections 3",
                    new GadgetRule.Requirement("commons-collections", "commons-collections",
                            new GadgetRule.Range("", "3.2.1")),
                    "InvokerTransformer / TransformedMap 反射链：只要存在反序列化入口即可命令执行",
                    "到「Payload 生成」搜 commonscollections，或用「预设链」的 CC 模板",
                    "payload.build"),
            GadgetRule.of("GAD-CC3-CHAINS", GadgetRule.Category.NATIVE, "CommonsCollections 3 全家族",
                    new GadgetRule.Requirement("commons-collections", "commons-collections",
                            new GadgetRule.Range("", "3.2.1")),
                    "含 LazyMap / TiedMapEntry / InstantiateTransformer 等多种入口形态，"
                            + "在 Shiro / Hessian / 原生反序列化场景下可按目标可用类自由选择",
                    "到「预设链」看全部 CC 变体，按目标 JDK 与依赖挑一条",
                    "payload.preset"),
            GadgetRule.of("GAD-CC4", GadgetRule.Category.NATIVE, "CommonsCollections 4",
                    GadgetRule.Requirement.in("commons-collections4", "org.apache.commons"),
                    "PriorityQueue + TransformingComparator 组链：入口与 CC3 不同，"
                            + "在 CC3 被过滤掉时常用",
                    "到「Payload 生成」搜 commonscollections4",
                    "payload.build"),
            GadgetRule.of("GAD-CC4-CHAINS", GadgetRule.Category.NATIVE, "CommonsCollections4 全家族",
                    GadgetRule.Requirement.in("commons-collections4", "org.apache.commons"),
                    "含 InstantiateTransformer / LazyMap / TreeBag 等入口组合",
                    "到「预设链」看 commonscollections4 分类",
                    "payload.preset"),
            GadgetRule.of("GAD-BEANUTILS", GadgetRule.Category.NATIVE, "CommonsBeanutils",
                    GadgetRule.Requirement.in("commons-beanutils", "commons-beanutils"),
                    "BeanComparator 配合 TemplatesImpl：Shiro / fastjson 场景最常用的变体之一",
                    "到「Payload 生成」搜 beanutis，或用「预设链」的 Beanutils 模板",
                    "payload.build"),
            GadgetRule.of("GAD-BEANUTILS-ATTR", GadgetRule.Category.NATIVE, "CommonsBeanutils1 属性链",
                    GadgetRule.Requirement.in("commons-beanutils", "commons-beanutils"),
                    "BeanComparator 需要目标存在 javax.xml TemplatesImpl，"
                            + "在未做 JAXP 加固的 JDK 上可用",
                    "到「预设链」挑 Beanutils 变体，按目标 JDK 选择",
                    "payload.preset"),
            GadgetRule.of("GAD-C3P0", GadgetRule.Category.NATIVE, "c3p0",
                    GadgetRule.Requirement.in("c3p0", "com.mchange"),
                    "PoolBackedDataSource + 反射工厂：可加载任意类，配合 BCEL 或远程类路径使用",
                    "到「恶意服务器」起 JNDI，用 c3p0 链做二次利用",
                    "service.servers"),
            GadgetRule.of("GAD-ROME", GadgetRule.Category.TOSTRING, "Rome ToStringBean",
                    GadgetRule.Requirement.in("rome", "com.rometools"),
                    "ToStringBean 组合类提供 toString 触发链，fastjson / Jackson 场景常用",
                    "到「toString 链」选 Rome 相关模板",
                    "payload.tostring"),
            GadgetRule.of("GAD-ROME-UTILS", GadgetRule.Category.TOSTRING, "Rome 工具链",
                    GadgetRule.Requirement.in("rome", "com.rometools"),
                    "配合 EqualsBean / ToStringBean 可绕开常见的 toString 黑名单",
                    "到「toString 链」自定义目标类后生成",
                    "payload.tostring"),
            GadgetRule.of("GAD-GROOVY", GadgetRule.Category.NATIVE, "Groovy",
                    GadgetRule.Requirement.in("groovy", "org.codehaus.groovy"),
                    "MethodClosure 可执行任意方法调用，配合 fastjson / Jackson 触发",
                    "到「Payload 生成」搜 groovy",
                    "payload.build"),
            GadgetRule.of("GAD-GROOVY-ALL", GadgetRule.Category.NATIVE, "Groovy 全量包",
                    GadgetRule.Requirement.in("groovy-all", "org.codehaus.groovy"),
                    "与 groovy 同源，存在于使用 groovy-all 聚合包的老工程",
                    "同上，按实际坐标选择",
                    "payload.build"),
            GadgetRule.of("GAD-JYTHON", GadgetRule.Category.NATIVE, "Jython",
                    GadgetRule.Requirement.in("jython-standalone", "org.python"),
                    "PyFunction / PyObject 可被用于构造反序列化链，属于「有它就有更多链可选」",
                    "到「Payload 生成」搜 jython",
                    "payload.build"),
            GadgetRule.of("GAD-JYTHON-CORE", GadgetRule.Category.NATIVE, "Jython（jython 坐标）",
                    GadgetRule.Requirement.in("jython", "org.python"),
                    "与 jython-standalone 同源，坐标不同，命中判定必须分别列出",
                    "到「Payload 生成」搜 jython",
                    "payload.build"),
            GadgetRule.of("GAD-BCEL", GadgetRule.Category.BCL, "BCEL ClassLoader",
                    GadgetRule.Requirement.in("bcel", "com.sun.org.apache.bcel"),
                    "BCEL ClassLoader 可直接加载传入的字节码字符串，fastjson 场景的常用变体",
                    "到「Payload 生成」搜 bcel",
                    "payload.build"),
            GadgetRule.of("GAD-SPRING-CORE", GadgetRule.Category.TEMPLATE, "Spring core（Spring4Shell 相关）",
                    GadgetRule.Requirement.in("spring-core", "org.springframework"),
                    "ClassLoader 相关的表达力加上 Spring 的 bean 加载能力，"
                            + "常用于 Spring 参数绑定类漏洞的载荷落地",
                    "到「Payload 生成」搜 spring",
                    "payload.build"),
            GadgetRule.of("GAD-XALAN", GadgetRule.Category.TEMPLATE, "Xalan 内部 TemplatesImpl",
                    GadgetRule.Requirement.in("xalan", "xalan"),
                    "提供 TemplatesImpl 的另实现，CC 链在 javax.xml 受限时可换入口",
                    "到「预设链」按目标 XML 实现选择链",
                    "payload.preset"),
            GadgetRule.of("GAD-ASPECTJ", GadgetRule.Category.NATIVE, "AspectJWeaver",
                    GadgetRule.Requirement.in("aspectjweaver", "org.aspectj"),
                    "StoreableCachingMap 的写入能力可被拼成文件写入链，"
                            + "在 fastjson 与原生反序列化场景都有变体",
                    "到「Payload 生成」搜 aspectjweaver",
                    "payload.build"),
            GadgetRule.of("GAD-JDK7U21", GadgetRule.Category.NATIVE, "JDK7u21 原生链",
                    new GadgetRule.Requirement("commons-collections", "commons-collections",
                            new GadgetRule.Range("", "3.2.1")),
                    "JDK7u21 的 AnnotationInvocationHandler 链需要集合类配合；"
                            + "该 JDK 版本已停止更新，链的可用性取决于目标运行环境",
                    "到「预设链」看 jdk7u21 分类，先确认目标 JDK 版本",
                    "payload.preset"),

            // ------------------------------------------------------------ Hessian
            GadgetRule.of("GAD-HESSIAN-ROME", GadgetRule.Category.HESSIAN, "Hessian + Rome",
                    GadgetRule.Requirement.in("hessian", "com.caucho"),
                    "Hessian 反序列化可实例化任意类，与 Rome 的 toString 触发组合成链",
                    "到「Payload 生成」搜 hessian 后按载体提交",
                    "payload.build"),
            GadgetRule.of("GAD-HESSIAN-BEANUTILS", GadgetRule.Category.HESSIAN, "Hessian + Beanutils",
                    GadgetRule.Requirement.in("hessian", "com.caucho"),
                    "Hessian 的 map / 类型系统可承载 BeanComparator 链，"
                            + "在只暴露 Hessian 协议的接口上最常用",
                    "到「预设链」挑 hessian 分类",
                    "payload.preset"),

            // ------------------------------------------------------------ JDBC / 驱动
            GadgetRule.of("GAD-JDBC-MYSQL", GadgetRule.Category.JDBC, "MySQL 驱动（恶意服务端）",
                    new GadgetRule.Requirement("mysql-connector-java"),
                    "驱动可被诱导向攻击者控制的 MySQL 服务端建连，"
                            + "配合本工具的 FakeMySQL 服务端读取到反序列化数据",
                    "到「恶意服务器」起 FakeMySQL，再用「预设链」出链",
                    "service.servers"),
            GadgetRule.of("GAD-JDBC-MYSQL8", GadgetRule.Category.JDBC, "MySQL 驱动（新坐标 8.x）",
                    new GadgetRule.Requirement("mysql-connector-j"),
                    "8.x 起坐标改为 mysql-connector-j，判定必须按新坐标识别，"
                            + "漏掉这一条会让新版目标的 JDBC 链整段消失",
                    "到「恶意服务器」起 FakeMySQL",
                    "service.servers"),
            GadgetRule.of("GAD-JDBC-PG", GadgetRule.Category.JDBC, "PostgreSQL 驱动（socketFactory）",
                    GadgetRule.Requirement.in("postgresql", "org.postgresql"),
                    "socketFactory / socketFactoryArg 参数可指定任意类，"
                            + "配合恶意服务端达成 RCE 的经典路径",
                    "到「恶意服务器」起 TCP 监听，再用连接串触发",
                    "service.servers"),
            GadgetRule.of("GAD-JDBC-H2", GadgetRule.Category.JDBC, "H2 驱动（INIT 脚本）",
                    GadgetRule.Requirement.in("h2", "com.h2database"),
                    "INIT=RUNSCRIPT 可从远程加载 SQL 脚本，属可控数据源场景的常用手法",
                    "到「恶意服务器」起 HTTP 托管脚本，再用连接串引用",
                    "service.servers"),
            GadgetRule.of("GAD-JDBC-DERBY", GadgetRule.Category.JDBC, "Derby 驱动（远程类加载）",
                    GadgetRule.Requirement.in("derby", "org.apache.derby"),
                    "老版本 Derby 允许从远端加载类定义，配合恶意服务端可落地字节码",
                    "到「恶意服务器」起 HTTP，再用连接串引用",
                    "service.servers"),

            // ------------------------------------------------------------ fastjson
            GadgetRule.of("GAD-FASTJSON-CC", GadgetRule.Category.FASTJSON, "fastjson + CC3",
                    GadgetRule.Requirement.in("fastjson", "com.alibaba"),
                    "fastjson autoType 可加载 commons-collections 链，"
                            + "是最常见的 fastjson 反序列化组合",
                    "到「Payload 生成」用 fastjson 载体，链内选 commonscollections",
                    "payload.build"),
            GadgetRule.of("GAD-FASTJSON-BEANUTILS", GadgetRule.Category.FASTJSON, "fastjson + Beanutils",
                    GadgetRule.Requirement.in("fastjson", "com.alibaba"),
                    "在不引入集合类但引入 beanutils 的工程上，这条组合通常是唯一可用的入口",
                    "到「Payload 生成」用 fastjson 载体，链内选 beanutis",
                    "payload.build"),
            GadgetRule.of("GAD-FASTJSON-BCEL", GadgetRule.Category.FASTJSON, "fastjson + BCEL",
                    GadgetRule.Requirement.in("fastjson", "com.alibaba"),
                    "通过 BCEL ClassLoader 加载字节码，可绕开对常见集合类的黑名单",
                    "到「Payload 生成」用 fastjson 载体，链内选 bcel",
                    "payload.build"),
            GadgetRule.of("GAD-FASTJSON-GROOVY", GadgetRule.Category.FASTJSON, "fastjson + Groovy",
                    GadgetRule.Requirement.in("fastjson", "com.alibaba"),
                    "1.2.69 之后的 autoType 绕过通常需要额外依赖，Groovy 是其中最容易凑齐的一种",
                    "到「Payload 生成」用 fastjson 载体，链内选 groovy",
                    "payload.build"),
            GadgetRule.of("GAD-FASTJSON2", GadgetRule.Category.FASTJSON, "fastjson2 + 集合类",
                    GadgetRule.Requirement.in("fastjson2", "com.alibaba"),
                    "fastjson2 的 autoType 处理与新版本绕过不适用 fastjson1 的结论，需单独判定",
                    "到「Payload 生成」按 fastjson2 场景试链，并核对目标实际版本",
                    "payload.build"),

            // ------------------------------------------------------------ Jackson
            GadgetRule.of("GAD-JACKSON-C3P0", GadgetRule.Category.JACKSON, "Jackson + c3p0",
                    GadgetRule.Requirement.in("jackson-databind", "com.fasterxml.jackson.core"),
                    "开启 DefaultTyping 时 Jackson 可实例化任意类，"
                            + "与 c3p0 的反射工厂组合可以落地任意类加载",
                    "到「Payload 生成」搜 jackson 后按载体提交",
                    "payload.build"),
            GadgetRule.of("GAD-JACKSON-ROME", GadgetRule.Category.JACKSON, "Jackson + Rome",
                    GadgetRule.Requirement.in("jackson-databind", "com.fasterxml.jackson.core"),
                    "Rome 的 ToStringBean 作为 Jackson 的 toString 触发出口，"
                            + "在多态反序列化场景最常用",
                    "到「toString 链」生成载荷后填入 Jackson 载体",
                    "payload.tostring"),
            GadgetRule.of("GAD-JACKSON-SPRING", GadgetRule.Category.JACKSON, "Jackson + Spring（JNDI 出口）",
                    GadgetRule.Requirement.in("jackson-databind", "com.fasterxml.jackson.core"),
                    "Spring 的 JndiObjectFactoryBean 可作为 JNDI 出口，"
                            + "在目标 JDK 未限制 JNDI 时直接落地",
                    "到「恶意服务器」起 JNDI，再用 Jackson 载荷触发",
                    "service.servers"),

            // ------------------------------------------------------------ toString 触发
            GadgetRule.of("GAD-TOSTRING-COMMONS", GadgetRule.Category.TOSTRING, "toString 通用出口（JDK 内置）",
                    new GadgetRule.Requirement("commons-collections", "commons-collections",
                            new GadgetRule.Range("", "3.2.1")),
                    "BadAttributeValueExpException / EventHandler 可把 toString 调用导向可控对象的读出方法",
                    "到「toString 链」自定义目标类后生成",
                    "payload.tostring"),
            GadgetRule.of("GAD-TOSTRING-ROME-FASTJSON", GadgetRule.Category.TOSTRING, "toString + fastjson",
                    GadgetRule.Requirement.in("rome", "com.rometools"),
                    "fastjson 在解析嵌套对象时会调用 toString，与 Rome 组合是常见的无 autoType 触发路径",
                    "到「toString 链」选 Rome 模板，再用 fastjson 载体提交",
                    "payload.tostring"),

            // ------------------------------------------------------------ 模板与表达式
            GadgetRule.of("GAD-TEMPLATE-VELOCITY", GadgetRule.Category.TEMPLATE, "Velocity",
                    GadgetRule.Requirement.in("velocity", "org.apache.velocity"),
                    "模板引擎可执行带反射的表达式，在服务端渲染场景可直接落命令执行",
                    "到「Payload 生成」搜 velocity",
                    "payload.build"),
            GadgetRule.of("GAD-TEMPLATE-FREEMARKER", GadgetRule.Category.TEMPLATE, "Freemarker",
                    GadgetRule.Requirement.in("freemarker", "org.freemarker"),
                    "内置的 new / 反射内置函数可绕过简单黑名单",
                    "到「Payload 生成」搜 freemarker",
                    "payload.build"),
            GadgetRule.of("GAD-TEMPLATE-EL", GadgetRule.Category.TEMPLATE, "javax.el 表达式",
                    GadgetRule.Requirement.in("javax.el", "org.glassfish"),
                    "EL 表达式可调用任意静态方法，是表达式注入类漏洞的通用出口",
                    "到「Payload 生成」搜 el 后按目标框架选择提交方式",
                    "payload.build"),
            GadgetRule.of("GAD-TEMPLATE-EL-IMPL", GadgetRule.Category.TEMPLATE, "javax.el 实现（tomcat 坐标）",
                    GadgetRule.Requirement.in("el-api", "org.apache.tomcat"),
                    "Tomcat 自带的 EL 实现，坐标与 glassfish 不同，"
                            + "漏掉这一条会让容器内应用的表达式链判定为空",
                    "同上，按容器实际坐标选择",
                    "payload.build")
    ));

    private GadgetRules() {
    }

    /** 全部内置 gadget 规则，只读。 */
    public static List<GadgetRule> all() {
        return RULES;
    }

    /** 规则条数，供界面摘要显示「本次用的是哪一版规则」。 */
    public static int count() {
        return RULES.size();
    }

    /** 按标识取规则；找不到返回 null。 */
    public static GadgetRule of(String id) {
        if (id == null) return null;
        for (GadgetRule rule : RULES) {
            if (rule.id.equalsIgnoreCase(id.trim())) return rule;
        }
        return null;
    }

    /** 全部规则类型，按表中出现顺序去重；报告按这个顺序分组。 */
    public static List<String> categories() {
        List<String> categories = new ArrayList<String>();
        for (GadgetRule rule : RULES) {
            if (!categories.contains(rule.category)) categories.add(rule.category);
        }
        return categories;
    }
}
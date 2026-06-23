package com.nanobot;

import java.io.InputStream;
import java.util.Properties;

/**
 * 包级版本号与常量定义。
 *
 * <p>Python 的 __getattr__ 惰性加载在 JVM 中不需要——类加载本身就是按需的。
 * 版本获取采用三级回退：MANIFEST.MF → build.properties → 硬编码兜底值。
 *
 * <p>对标 Python {@code nanobot/__init__.py:20-29 _resolve_version(), __version__, __logo__}。
 */
public final class NanobotVersion {

    /** 包级 logo 常量，对标 Python {@code __init__.py:29 __logo__ = "🐈"} */
    public static final String LOGO = "🐈";

    /** 版本获取的最终兜底值，对标 Python {@code _resolve_version()} 中的 "0.2.1" */
    private static final String FALLBACK_VERSION = "0.2.1";

    /**
     * 模块导入时求值一次并缓存。对标 Python {@code __init__.py:28 __version__}
     * 在模块首次 import 时求值并保存为模块属性的语义。
     */
    public static final String VERSION = resolveVersion();

    private NanobotVersion() {}

    /**
     * 三级回退获取当前版本号：
     * <ol>
     *   <li>JAR MANIFEST.MF 的 Implementation-Version（对标 Python pkg metadata version）</li>
     *   <li>classpath 下的 META-INF/build.properties</li>
     *   <li>硬编码兜底 "0.2.1"</li>
     * </ol>
     *
     * @return 解析出的版本字符串
     */
    // 对标 Python __init__.py:20-25 _resolve_version()
    public static String resolveVersion() {
        String v = readManifestVersion();
        if (v != null && !v.isBlank()) return v;

        v = readBuildPropertiesVersion();
        if (v != null && !v.isBlank()) return v;

        return FALLBACK_VERSION;
    }

    /** 读取 JAR MANIFEST.MF 中的 Implementation-Version。对标 Python _pkg_version("nanobot-ai") */
    private static String readManifestVersion() {
        Package pkg = Package.getPackage("com.nanobot");
        if (pkg != null) {
            String v = pkg.getImplementationVersion();
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    /** 回退读取 classpath 构建属性文件。对标 Python _read_pyproject_version() */
    private static String readBuildPropertiesVersion() {
        try (InputStream is = NanobotVersion.class.getResourceAsStream("/META-INF/build.properties")) {
            if (is != null) {
                Properties props = new Properties();
                props.load(is);
                String v = props.getProperty("version");
                if (v != null && !v.isBlank()) return v;
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}

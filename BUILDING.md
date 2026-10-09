# 构建

使用 **JDK 21、Gradle 8.12、Fabric Loom 1.9 系列**。本仓库不附本机 JDK、Gradle 缓存或 Gradle Wrapper 二进制，先在你的环境中安装匹配工具。`settings.gradle` 使用 Fabric Maven、Maven Central 与 Gradle Plugin Portal；首次构建需要网络。

```powershell
gradle --version
gradle clean build --console=plain
```

产物应位于 `build/libs/`。安装普通重映射生产 JAR；`-sources.jar` 与开发 JAR 不装入游戏。发布副本的构建脚本保留 main/client/test 生产源码编译，将原本机 repair Maven、外部测试世界与 smoke 入口去除，不自动下载或安装游戏。

本轮只核查配置、JSON、文档链接、脚本语法、发布文件与来源快照。未运行 Gradle 编译或游戏，不能用此记录代替构建和多人验证。

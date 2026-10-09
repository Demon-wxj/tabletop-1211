# 构建

先安装 **JDK 21 和 Gradle 8.12**。项目使用 Fabric Loom 1.9 系列；仓库没有 Gradle Wrapper，首次构建需要联网获取 Maven 依赖。

```powershell
gradle --version
gradle clean build --console=plain
```

构建产物在 `build/libs/`。安装生产 JAR，不要把 `-sources.jar` 或开发 JAR 放入游戏。

# 项目约定

## 技术栈

- Windows 10 或更新版本，x64
- JDK 21 toolchain
- Kotlin/JVM
- Compose Desktop
- 在可行时使用稳定依赖
- UTF-8 源文件

## 标识

每个工具包含四个不同的标识字段：

- `displayName`：面向用户的中文名称。
- `projectName`：用于 Gradle 项目和可执行文件的 PascalCase 英文名称。
- `id`：位于 `dev.towertools` 下、稳定的小写 Application ID。
- `directoryName`：位于 `apps/` 下的 kebab-case 源目录名称。

Kotlin package 与 Gradle group 均等于 Application ID。ID 发布后不得复用给其他工具，也不得修改。

作者/vendor 为 `Alice-tower`。默认不添加开源许可证。

## 版本

应用从 `1.0.0` 开始，并使用 semantic version。成功完成 staged build 后，同一 Application ID 的便携输出会替换旧输出。

## 用户界面

所有工具必须提供 GUI。默认模板提供中文界面、系统明暗主题支持、标准窗口尺寸、可见的错误区域、日志与单实例激活。

小型应用应尽量保持结构简单；只有在功能集需要时才添加架构层。

## Agent 指令

所有项目均继承仓库级 `AGENTS.md`。默认不要在每个工具内创建另一个 `AGENTS.md`；仅当某个工具具有不同于仓库默认规则的长期约束时，才添加工具专属文件。

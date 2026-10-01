# 静读第三方依赖与分发核对

核对日期：2026-10-01。核对对象是实际 `outputs/tools/dev.towertools.towerreader/app/`，而非仅依据 Gradle 声明。

## 新增实际组件

- PDFBox、PDFBox IO、FontBox 3.0.8，Commons Logging 1.4.0：Apache-2.0，全部包内 LICENSE/NOTICE 已原样提取到根 `third-party/licenses/`。PDFBox 的完整 LICENSE 还包含资源字体等第三方许可，不能只用通用 Apache 文本替代。
- jsoup 1.23.2：MIT，包内 `META-INF/jsoup/LICENSE` 已原样提取。
- OpenJFX 21.0.12 Windows x64 的 base、graphics、controls、media、web、swing 六模块：GPL v2 与 Classpath exception；同时保留 assembly exception。Maven JAR 不含完整 legal，已从官方相同版本 Windows SDK 提取这六模块的全部 28 份 legal 文件。
- OpenJFX 原生代码的 IJG JPEG、Mesa、DirectShow、GLib、GStreamer、libffi、ICU、libxml2、libxslt、WebKit 许可和版权均保留在 `third-party/licenses/OpenJFX-21.0.12/`。WebKit 文本完整保留其内部 LGPL、MPL、Apache、Unicode、BSD 等条款。
- 已有 Compose、Kotlin、Skiko、JNA、SQLite 等组件继续适用根第三方声明。

## 二进制与源码来源

官方 SDK：
https://download2.gluonhq.com/openjfx/21.0.12/openjfx-21.0.12_windows-x64_bin-sdk.zip

SHA-256：`78ab10816e71ed51808bc5853bb26f38cf35b550a4cec233dbc4fbf5a3e8574c`。
SDK 的 `javafx.properties` 标识 `javafx.runtime.version=21.0.12+2`。实际打包六个 Maven Windows JAR 的全部 58 个 DLL/ICU 数据项与此 SDK 的对应项逐个 SHA-256 一致。

完整发布源码来自官方 OpenJDK 更新仓库：
https://codeload.github.com/openjdk/jfx21u/zip/refs/tags/21.0.12%2B3

标签 `21.0.12+3` 指向提交 `6ae7c978ef7cc7900281d49712cea5bf529858d0`（2026-07-21，发布日合并安全修订）。SHA-256：`5c5b9826c8bfbfd571ba97e15128055f69226d968a10e913f2194ee5ec29b09a`。

官方仓库查询只找到 `21.0.12+1` 和 `21.0.12+3`，没有 `21.0.12+2` 标签。Maven POM 指向 OpenJDK 源码仓库但不包含精确提交。Gluon 官方下载页没有独立完整 native 源码链接。为避免错误描述，不将二进制构建编号 +2 与源码标签 +3 声称为相同编号。

核对结果：SDK 的 2352 个非生成 Java 文件与源码 +3 逐字一致；10 个 native legal 文件逐字一致。SDK 其余 110 个生成 Java 文件另由 SDK `src.zip` 保存。完整源码包含 WebCore、GLib、GStreamer、其他原生模块及构建脚本，SDK 自带 `src.zip` 只有 Java 源码，不能单独替代它。

上述验证证明同发布版本与已核对文件的一致性；发行方未提供 +2 二进制精确 native 构建提交，无法仅凭 Java/legal 一致证明每个 native 源码文件的精确编译修订。本记录如实保留该来源粒度限制。

源码包位于 Git 忽略的 `outputs/third-party-sources/tower-reader/`：

- `openjfx-21.0.12-source.zip`：完整官方发布源码。
- `openjfx-21.0.12-sdk-generated-java-source.zip`：SDK 原始 `src.zip`，SHA-256 `417e52996929692bd64c3aae4a9f7a12c512816637f7a61588452bdefc8f6389`。
- `SOURCES.md`：来源、哈希和核对说明。

`createDistributable` 自动核验缓存 SHA-256；冷构建缺少源码时从上述官方 URL 下载，必要时下载 SDK 提取生成 Java 源码，临时 SDK 校验提取后删除。准备任务采用同目录临时文件和原子发布。构建把两个源码压缩包、跟踪的 `SOURCES.md` 与本记录复制到便携目录的 `third-party-sources/`。首次准备需要网络，运行时不下载源码。

分发时应随静读便携目录保留这些源码压缩包与许可证。不要将源码大包提交到 Git。修改 OpenJFX 原生代码时必须更新对应源码，不能沿用本次未经修改的上游记录。

## Microsoft 原生运行时

OpenJFX graphics Windows JAR 含 `msvcp140*.dll`、`vcruntime140*.dll`、`ucrtbase.dll`、`api-ms-win-*.dll`。实测 `msvcp140.dll` 为 14.36.32532.0，`ucrtbase.dll` 为 10.0.10240.16384，版权属 Microsoft。

SDK 没有这些运行时的独立微软许可文件。已从微软官方 Visual Studio 2022 Community 许可页关联的原始文档完整提取条款到 `third-party/licenses/Microsoft-Visual-Studio-2022-Community-License.txt`，用于保留官方再分发条件参考，并记录其来源；没有将微软组件误标为 GPL/MIT。

官方参考：

- https://learn.microsoft.com/en-us/visualstudio/releases/2022/redistribution
- https://visualstudio.microsoft.com/license-terms/vs2022-ga-community/
- https://visualstudio.microsoft.com/wp-content/uploads/2021/11/Visual-Studio-2022-Community-License-EN.docx

微软官方再分发清单要求按相应 Visual Studio 许可条款分发未经修改的组件，并对分发者资格及最终用户条款设有条件。本核对保留这些事实，不因 Gluon SDK 携带 DLL 就推定所有分发场景已满足微软条件，也不替发行方或仓库维护者作未经核实的法律承诺。

## 微信读书 WebView2（1.2.0）

新增实际组件只有 Microsoft WebView2 SDK 1.0.4258.31 的 Windows x64 WebView2Loader.dll（194,912 字节）。它作为 JAR 资源打包，首次使用时复制到静读数据目录的 weread/cache/ 下。复用原有 JNA 5.19.1 调用 COM，不增加 JVM 第三方依赖、.NET 运行时或 C++ 构建步骤。WebView2 Evergreen Runtime 使用机器已有安装，不随包分发。

来源：https://api.nuget.org/v3-flatcontainer/microsoft.web.webview2/1.0.4258.31/microsoft.web.webview2.1.0.4258.31.nupkg

- 官方 SDK 包 SHA-256：56f7f4b8bf9aee4b8efefbbdd4f67d5f74ebd1b100ed0806da71bf76af481aa9。
- 提取路径：build/native/x64/WebView2Loader.dll。
- DLL SHA-256：3426dcc55fdfb8b5e7ac623cf33b4ccf283fbf68a0c5a65bddfdb4d749a8abee。
- 本机 Authenticode 核验为 Valid，签名者 Microsoft Corporation。
- 包内 LICENSE.txt 为 Microsoft BSD-3-Clause 文本；LICENSE.txt 和 NOTICE.txt 已完整保存到 third-party/licenses/WebView2-SDK-LICENSE.txt 与 WebView2-SDK-NOTICE.txt。SDK NOTICE 是上游完整包的声明，保留它不代表这里分发了其所有 .NET 组件。

prepareWebViewLoader 校验 SDK 下载哈希并提取 DLL 为 generated/webview2 构建资源；缓存留在 outputs/build-dependencies/tower-reader/。首次源码构建需要联网，缓存存在后复用。升级 SDK 时须重新核对 COM 接口、官方包摘要、签名、许可和实际便携 JAR 内的 DLL。

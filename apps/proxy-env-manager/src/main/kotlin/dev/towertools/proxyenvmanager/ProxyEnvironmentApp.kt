package dev.towertools.proxyenvmanager

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Card
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@Composable
fun ProxyEnvironmentApp(service: ProxyEnvironmentService) {
    var environment by remember { mutableStateOf(runCatching(service::read).getOrNull()) }
    var message by remember { mutableStateOf("读取的是当前用户环境变量，不包含系统级变量。") }
    var isError by remember { mutableStateOf(environment == null) }

    fun currentValues(value: ProxyEnvironment?): Map<String, String?> = linkedMapOf(
        ProxyVariables.HTTP_PROXY to value?.httpProxy,
        ProxyVariables.HTTPS_PROXY to value?.httpsProxy,
        ProxyVariables.ALL_PROXY to value?.allProxy,
        ProxyVariables.NO_PROXY to value?.noProxy,
    )

    fun execute(successMessage: String, operation: () -> ProxyEnvironment) {
        runCatching(operation)
            .onSuccess {
                environment = it
                message = successMessage
                isError = false
            }
            .onFailure {
                AppLog.logger.warning("Proxy environment operation failed: ${it.message}")
                message = "操作失败：${it.message ?: it.javaClass.simpleName}"
                isError = true
            }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("代理环境变量", style = MaterialTheme.typography.h4)
        Text(
            "查看并管理当前 Windows 用户的四项代理环境变量。",
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
        )

        val values = currentValues(environment)
        val mismatchedNames = ProxyVariables.presets.filter { (name, preset) ->
            values[name] != preset
        }.keys
        if (environment != null && mismatchedNames.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = MaterialTheme.colors.error.copy(alpha = 0.12f),
                elevation = 0.dp,
            ) {
                Text(
                    "以下变量与预设不一致：${mismatchedNames.joinToString("、")}。",
                    modifier = Modifier.padding(14.dp),
                    color = MaterialTheme.colors.error,
                )
            }
        }

        ProxyVariables.presets.forEach { (name, preset) ->
            ProxyVariableCard(name, values[name], preset)
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            Button(onClick = {
                execute("已将四个变量设置为预设值", service::setLocalProxy)
            }) {
                Text("一键设置四个变量")
            }
            Spacer(Modifier.width(12.dp))
            Button(
                onClick = { execute("已删除四个代理环境变量", service::clear) },
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = MaterialTheme.colors.error,
                    contentColor = MaterialTheme.colors.onError,
                ),
            ) {
                Text("删除四个变量")
            }
                Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = { execute("已刷新", service::read) }) {
                Text("刷新")
            }
        }

        Text(
            message,
            color = if (isError) MaterialTheme.colors.error else MaterialTheme.colors.primary,
        )
        Text(
            "修改后，请重启需要使用新代理配置的程序。",
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
        )
    }
}

@Composable
private fun ProxyVariableCard(name: String, value: String?, preset: String) {
    Card(modifier = Modifier.fillMaxWidth(), elevation = 2.dp) {
        Column(modifier = Modifier.fillMaxWidth().padding(18.dp)) {
            Text(name, style = MaterialTheme.typography.subtitle1)
            Spacer(Modifier.height(8.dp))
            SelectionContainer {
                Text(
                    text = when {
                        value == null -> "未设置"
                        value.isEmpty() -> "（空字符串）"
                        else -> value
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colors.onSurface.copy(alpha = 0.05f))
                        .padding(12.dp),
                    color = if (value == null) Color.Gray else MaterialTheme.colors.onSurface,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (value == preset) "符合预设" else "与预设不一致；预设值：$preset",
                color = if (value == preset) MaterialTheme.colors.primary else MaterialTheme.colors.error,
                style = MaterialTheme.typography.caption,
            )
        }
    }
}

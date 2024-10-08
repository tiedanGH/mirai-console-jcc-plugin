import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.mamoe.mirai.console.command.CommandManager.INSTANCE.commandPrefix
import net.mamoe.mirai.console.command.CommandManager.INSTANCE.register
import net.mamoe.mirai.console.command.CommandManager.INSTANCE.unregister
import net.mamoe.mirai.console.plugin.jvm.JvmPluginDescription
import net.mamoe.mirai.console.plugin.jvm.KotlinPlugin
import net.mamoe.mirai.contact.Contact
import net.mamoe.mirai.contact.Group
import net.mamoe.mirai.contact.User
import net.mamoe.mirai.event.globalEventChannel
import net.mamoe.mirai.event.subscribeMessages
import net.mamoe.mirai.message.data.*
import net.mamoe.mirai.utils.info

object JCompilerCollection : KotlinPlugin(
    JvmPluginDescription(
        id = "top.jie65535.mirai-console-jcc-plugin",
        name = "J Compiler Collection",
        version = "1.1.0",
    ) {
        author("jie65535")
        info("""在线编译器集合""")
    }
) {
    const val CMD_PREFIX = "run"
    private const val MSG_TRANSFER_LENGTH = 550
    private const val MSG_MAX_LENGTH = 800

    var THREAD = 0

    override fun onEnable() {
        logger.info { "Plugin loaded" }
        JccCommand.register()
        JccPluginData.reload()

        CommandPastebin.register()
        CommandRun.register()
        PastebinConfig.reload()
        PastebinData.reload()

        globalEventChannel()
            .parentScope(this)
            .subscribeMessages {
                content {
                    message.firstIsInstanceOrNull<PlainText>()?.content?.trimStart()?.startsWith(CMD_PREFIX) == true
                } reply {
                    val msg = message.firstIsInstance<PlainText>().content.trimStart().removePrefix(CMD_PREFIX).trim()
                    if (msg.isBlank()) {
                        return@reply "请输入正确的命令！例如：\n$CMD_PREFIX python print(\"Hello world\")"
                    }

                    val index = msg.indexOfFirst(Char::isWhitespace)
                    val language = if (index >= 0) msg.substring(0, index) else msg
                    if (!GlotAPI.checkSupport(language))
                        return@reply "不支持这种编程语言\n${commandPrefix}jcc list 列出所有支持的编程语言\n" +
                                "如果要执行保存好的pastebin代码，请在指令前添加 $commandPrefix"
                    if (THREAD >= 4) {
                        val builder = MessageChainBuilder()
                        if (subject is Group) {
                            builder.add(At(sender))
                            builder.add("\n")
                        }
                        builder.append("当前有 $THREAD 个进程正在执行或等待冷却，请等待几秒后再次尝试")
                        return@reply builder.build()
                    }

                    try {
                        // 检查命令的引用
                        val quote = message[QuoteReply]
                        var input: String? = null
                        // 支持运行引用的消息的代码
                        var code = if (quote != null) {
                            // run c [input]
                            if (index >= 0) {
                                input = msg.substring(index).trim()
                            }
                            quote.source.originalMessage.content
                        } else if (index >= 0) {
                            msg.substring(index).trim()
                        } else {
                            return@reply "$CMD_PREFIX $language\n" + GlotAPI.getTemplateFile(language).content
                        }

                        // 如果是引用消息，则不再从原消息中分析。否则，还要从消息中判断是否存在输入参数
                        val si = if (quote != null) 0 else code.indexOfFirst(Char::isWhitespace)
                        // 尝试得到url
                        val url = if (si > 0) {
                            code.substring(0, si)
                        } else {
                            code
                        }
                        // 如果参数是一个ubuntu pastebin的链接，则去获取具体代码
                        if (UbuntuPastebinHelper.checkUrl(url)) {
                            if (si > 0) {
                                // 如果确实是一个链接，则链接后面跟的内容就是输入内容
                                input = code.substring(si+1)
                            }
                            logger.info("从 $url 中获取代码")
                            code = UbuntuPastebinHelper.get(url)
                            if (code.isBlank()) {
                                return@reply "未获取到有效代码"
                            }
                        }

                        CoroutineScope(Dispatchers.IO).launch {
                            THREAD++; delay(2000); THREAD--
                        }

                        // subject.sendMessage("正在执行，请稍等...")
                        logger.info("请求执行代码\n$code")
                        val builder = runCode(subject, sender, language, code, input)

                        CoroutineScope(Dispatchers.IO).launch {
                            THREAD++; delay(5000); THREAD--
                        }
                        when (builder) {
                            is MessageChainBuilder -> return@reply builder.build()
                            is ForwardMessage-> return@reply builder
                            else -> return@reply "[处理消息失败] 不识别的消息类型"
                        }
                    } catch (e: Exception) {
                        logger.warning(e)
                        return@reply "执行失败\n原因：${e.message}"
                    }
                }
        }
    }

    fun runCode(subject: Contact?, sender: User?, language: String, code: String, input: String?): Any {
        val result = GlotAPI.runCode(language, code, input)
        val builder = MessageChainBuilder()
        if (result.message.isNotEmpty()) {
            builder.append("执行失败\n收到来自glot接口的消息：")
            builder.append(result.message)
            return builder
        }
        var c = 0
        if (result.stdout.isNotEmpty()) c++
        if (result.stderr.isNotEmpty()) c++
        if (result.error.isNotEmpty()) c++
        val title = c >= 2
        if (subject is Group) {
            builder.add(At(sender!!))
            builder.add("\n")
        }

        if (c == 0) {
            builder.add("没有任何结果呢~")
        } else {
            val sb = StringBuilder()
            if (result.error.isNotEmpty()) {
                sb.appendLine("error:")
                sb.append(result.error)
            }
            if (result.stdout.isNotEmpty()) {
                if (title) sb.appendLine("\nstdout:")
                sb.append(result.stdout)
            }
            if (result.stderr.isNotEmpty()) {
                if (title) sb.appendLine("\nstderr:")
                sb.append(result.stderr)
            }
            // 输出内容过长，改为转发消息
            if (sb.length > MSG_TRANSFER_LENGTH && PastebinConfig.enable_ForwardMessage) {
                var currentCount = 0
                val resultString = StringBuilder()
                var tooLong = false
                for (char in sb) {
                    val charCount = if (char.code in 0x4E00..0x9FFF) 3 else 1
                    if (currentCount + charCount <= 14000) {
                        resultString.append(char)
                        currentCount += charCount
                    } else {
                        tooLong = true
                        break
                    }
                }
                val forward: ForwardMessage = buildForwardMessage(subject!!) {
                    displayStrategy = object : ForwardMessage.DisplayStrategy {
                        override fun generateTitle(forward: RawForwardMessage): String = "输出过长，请查看聊天记录"
                        override fun generateBrief(forward: RawForwardMessage): String = "[输出内容]"
                        override fun generatePreview(forward: RawForwardMessage): List<String> =
                            if (tooLong) {
                                mutableListOf(
                                    "提示: 输出内容超过最大上限15000字符（5000中文字符），从14000开始已被截断",
                                    "输出内容: ${sb.substring(0, 30)}..."
                                )
                            } else {
                                mutableListOf("输出内容: ${sb.substring(0, 50)}...")
                            }

                        override fun generateSummary(forward: RawForwardMessage): String =
                            "输出长度总计 ${sb.length} 字符"
                    }
                    if (tooLong) {
                        subject.bot named "提示" says "输出内容超过最大上限15000字符（5000中文字符），从14000开始已被截断"
                        subject.bot named "输出内容" says resultString.toString()
                    } else {
                        subject.bot named "输出内容" says sb.toString()
                    }
                }
                return forward
            }
            // 非转发消息放刷屏截断
            if (sb.length > MSG_MAX_LENGTH) {
                sb.deleteRange(MSG_MAX_LENGTH, sb.length)
                sb.append("\n消息内容过长，已截断")
            }
            builder.append(sb.toString())
        }
        return builder
    }

    override fun onDisable() {
        JccCommand.unregister()
        CommandPastebin.unregister()
        CommandRun.unregister()
    }
}
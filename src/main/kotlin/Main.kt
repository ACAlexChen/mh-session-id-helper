// 太刀！！！

package net.ac_official.mhSessionIdHelper
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import io.ktor.client.*
import io.ktor.client.call.body
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.pingInterval
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.URLProtocol
import io.ktor.http.contentType
import io.ktor.http.path
import io.ktor.serialization.kotlinx.json.json
import io.ktor.websocket.*
import java.nio.file.Paths
import java.time.LocalDate
import kotlin.time.Duration.Companion.milliseconds

val json = Json {
  ignoreUnknownKeys = true
}

@Serializable
enum class PostType {
  @SerialName("message")
  MESSAGE,
  @SerialName("notice")
  NOTICE,
  @SerialName("request")
  REQUEST,
  @SerialName("meta_event")
  META_EVENT
}

@Serializable
enum class Role {
  @SerialName("owner")
  OWNER,
  @SerialName("admin")
  ADMIN,
  @SerialName("member")
  MEMBER
}

@Serializable
enum class MessageType {
  @SerialName("group")
  GROUP,
  @SerialName("private")
  PRIVATE
}

@Serializable
data class GroupData(
  var adminOnly: Boolean,
  var noticeEnabled: Boolean,
  val session: Session,
  var noticeId: Int? = null,
  val groupId: Int
)

@Serializable
data class Session(val world: MutableList<String>, val wilds: MutableList<String>)


class GroupManager(private val groupId: Int) {
  private val data: GroupData
  init {
    val file = File("data/$groupId.json")
    if (!file.exists()) {
      file.createNewFile()
      val defaultData = GroupData(
        adminOnly = false,
        noticeEnabled = false,
        noticeId = null,
        groupId = groupId,
        session = Session(mutableListOf(), mutableListOf())
      )
      file.writeText(json.encodeToString(defaultData))
      data = defaultData
    } else {
      data = json.decodeFromString(file.readText())
    }
  }

  suspend fun save() = withContext(Dispatchers.IO) {
    File("data/$groupId.json").writeText(json.encodeToString(data))
  }

  fun getAdminOnly() = data.adminOnly
  fun getNoticeEnabled() = data.noticeEnabled
  fun getWorldSessionList(): List<String> = data.session.world
  fun getWildsSessionList(): List<String> = data.session.wilds
  fun getNoticeId(): Int? = data.noticeId

  suspend fun setNoticeId(noticeId: Int) {
    data.noticeId = noticeId
    save()
  }

  fun getSessionString(): String {
    val strings = mutableListOf<String>()
    if (!data.session.world.isEmpty()) {
      strings.add("世界集会：")
      data.session.world.forEachIndexed { index, id -> strings.add("${index + 1}集：$id") }
    }
    if (!data.session.wilds.isEmpty()) {
      strings.add("荒野集会：")
      data.session.wilds.forEachIndexed { index, id -> strings.add("${index + 1}集：$id") }
    }
    if (strings.isEmpty()) {
      strings.add("你群药丸")
    }
    return strings.joinToString("\n")
  }

  suspend fun switchAdminOnly() {
    data.adminOnly = !data.adminOnly
    save()
  }

  suspend fun switchNoticeEnabled() {
    data.noticeEnabled = !data.noticeEnabled
    save()
  }

  suspend fun addWorldSession(sessionId: String) {
    data.session.world.add(sessionId)
    save()
  }

  suspend fun addWildsSession(sessionId: String) {
    data.session.wilds.add(sessionId)
    save()
  }

  suspend fun removeWorldSessionById(sessionId: String) {
    data.session.world.remove(sessionId)
    save()
  }

  suspend fun removeWildsSessionById(sessionId: String) {
    data.session.wilds.remove(sessionId)
    save()
  }

  suspend fun removeWorldSessionByIndex(index: Int) {
    data.session.world.removeAt(index)
    save()
  }

  suspend fun removeWildsSessionByIndex(index: Int) {
    data.session.world.removeAt(index)
    save()
  }

  suspend fun clearWorldSessionList() {
    data.session.world.clear()
    save()
  }

  suspend fun clearWildsSessionList() {
    data.session.wilds.clear()
    save()
  }
}

@Serializable
data class WsConfig(val ssl: Boolean, val host: String, val port: Int, val pingInterval: Int? = null, val accessToken: String? = null)

@Serializable
data class HttpConfig(val ssl: Boolean, val host: String, val port: Int, val accessToken: String? = null)

@Serializable
data class CommandConfig(val a: String)

@Serializable
data class Config(val wsConfig: WsConfig, val httpConfig: HttpConfig, val commandConfig: CommandConfig)

@Serializable
data class Event(
  val time: Int,
  @SerialName("self_id")
  val selfId: Int,
  @SerialName("post_type")
  val postType: PostType,
  @SerialName("message_id")
  val messageId: Int? = null,
  @SerialName("group_id")
  val groupId: Int? = null,
  val sender: Sender? = null,
  // val message: List<Message>? = null,
  @SerialName("message_type")
  val messageType: MessageType? = null,
  @SerialName("raw_message")
  val rawMessage: String? = null
)

//data class Message()

@Serializable
data class Sender(
  @SerialName("user_id")
  val userId: Long,
  val role: Role? = null
)

data class CommandMessage(val args: List<String>, val event: Event)

class CommandParser {
  private val commands = mutableMapOf<String, suspend CommandMessage.() -> Unit>()

  fun register(name: String, handler: suspend CommandMessage.() -> Unit) {
    commands[name] = handler
  }

  suspend fun parse(str: String, event: Event) {
    val tokens = str.split("\\s+".toRegex())
    if (tokens.isEmpty()) return

    val cmd = tokens[0]
    val args = tokens.drop(1)

    commands[cmd]?.let {
      CommandMessage(args, event).it()
    }
  }

}

@Serializable
data class SendGroupMsgArg(
  @SerialName("group_id")
  val groupId: Int,
  @SerialName("auto_escape")
  val autoEscape: Boolean = false,
  val message: List<SendGroupMsgMessageArg>
)

@Serializable
data class SendGroupMsgMessageArg(
  val type: String,
  val data: SendGroupMsgMessageDataArg
)

@Serializable
data class SendGroupMsgMessageDataArg(
  val text: String
)

@Serializable
data class SendGroupNoticeArg(
  @SerialName("group_id")
  val groupId: Int,
  val content: String,
  val image: String? = null
)

@Serializable
data class DelGroupNoticeArg(
  @SerialName("group_id")
  val groupId: Int,
  @SerialName("notice_id")
  val noticeId: Int
)

@Serializable
data class GetGroupNoticeArg(
  @SerialName("group_id")
  val groupId: Int
)

@Serializable
data class GetGroupNoticeResponse(
  val data: List<GetGroupNoticeDataResponse>
)

@Serializable
data class GetGroupNoticeDataResponse(
  @SerialName("notice_id")
  val noticeId: String, // 为什么这里是String，左右脑互搏吗
  val message: GetGroupNoticeDataMessageResponse
)

@Serializable
data class GetGroupNoticeDataMessageResponse(
  val text: String
)

class NapcatAPI(val httpConfig: HttpConfig) {
  private val client: HttpClient = HttpClient(CIO) {
    install(ContentNegotiation) {
      json()
    }
  }

  suspend fun sendGroupMsg(groupId: Int, msg: String) {
    client.post {
      url {
        protocol = if (httpConfig.ssl) {
          URLProtocol.HTTPS
        } else {
          URLProtocol.HTTP
        }
        httpConfig.accessToken?.let {
          header("Authorization", "Bearer $it")
        }
        host = httpConfig.host
        port = httpConfig.port
        path("/send_group_msg")
      }
      contentType(ContentType.Application.Json)
      setBody(
        SendGroupMsgArg(
          groupId = groupId,
          message = listOf(
            SendGroupMsgMessageArg(
              type = "text",
              data = SendGroupMsgMessageDataArg(
                text = msg
              )
            )
          )
        )
      )
    }
  }

  suspend fun sendGroupNotice(groupId: Int, content: String) {
    val image = "https://album.biliimg.com/bfs/new_dyn/0c2b1776814492e4270be1c9e96f99f259784373.jpg"
    client.post {
      url {
        protocol = if (httpConfig.ssl) {
          URLProtocol.HTTPS
        } else {
          URLProtocol.HTTP
        }
        httpConfig.accessToken?.let {
          header("Authorization", "Bearer $it")
        }
        host = httpConfig.host
        port = httpConfig.port
        path("/_send_group_notice")
      }
      contentType(ContentType.Application.Json)
      setBody(
        SendGroupNoticeArg(
          groupId = groupId,
          content = content
        )
      )
    }
    // 太可恶了为什么不把noticeId发过来
  }

  suspend fun getGroupNotice(groupId: Int): GetGroupNoticeResponse {
    val response = client.post {
      url {
        protocol = if (httpConfig.ssl) {
          URLProtocol.HTTPS
        } else {
          URLProtocol.HTTP
        }
        httpConfig.accessToken?.let {
          header("Authorization", "Bearer $it")
        }
        host = httpConfig.host
        port = httpConfig.port
        path("/_get_group_notice")
      }
      contentType(ContentType.Application.Json)
      setBody(
        GetGroupNoticeArg(
          groupId = groupId
        )
      )
    }
    return json.decodeFromString(response.bodyAsText())
  }

  suspend fun delGroupNotice(groupId: Int, noticeId: Int) {
    client.post {
      url {
        protocol = if (httpConfig.ssl) {
          URLProtocol.HTTPS
        } else {
          URLProtocol.HTTP
        }
        httpConfig.accessToken?.let {
          header("Authorization", "Bearer $it")
        }
        host = httpConfig.host
        port = httpConfig.port
        path("/_del_group_notice")
      }
      contentType(ContentType.Application.Json)
      setBody(
        DelGroupNoticeArg(
          groupId = groupId,
          noticeId = noticeId
        )
      )
    }
  }
}

val groupManagers = mutableMapOf<Int, GroupManager>()

fun Event.getGroupManager(): GroupManager? {
  return this.groupId?.let {
    val cache = groupManagers[it]
    return if (cache !== null) {
      cache
    } else {
      val groupManager = GroupManager(it)
      groupManagers[it] = groupManager
      groupManager
    }
  }
}

fun createCommand(commandConfig: CommandConfig, napcatAPI: NapcatAPI): CommandParser {
  return CommandParser().apply {
    register("查询") {
      val groupManager = event.getGroupManager() as GroupManager
      napcatAPI.sendGroupMsg(event.groupId!!, groupManager.getSessionString())
    }

    register("登记世界") {
      val groupManager = event.getGroupManager() as GroupManager
      if (groupManager.getAdminOnly() && (event.sender?.role !== Role.ADMIN && event.sender?.role !== Role.OWNER)) {
        napcatAPI.sendGroupMsg(event.groupId!!, "权限不足！")
        return@register
      }
      if (args.isEmpty()) {
        napcatAPI.sendGroupMsg(event.groupId!!, "未提供session id！")
        return@register
      }
      val sessionId = args[0]
      groupManager.addWorldSession(sessionId)
      napcatAPI.sendGroupMsg(event.groupId!!, "登记成功！")
      if (groupManager.getNoticeEnabled()) {
        groupManager.getNoticeId()?.let {
          napcatAPI.delGroupNotice(event.groupId, it)
        }
        val date = LocalDate.now()
        val content = "${date.month.value}月${date.dayOfMonth}日集会：\n${groupManager.getSessionString()}"
        napcatAPI.sendGroupNotice(event.groupId, content)
        val noticeData = napcatAPI.getGroupNotice(event.groupId)
        noticeData.data.find { it.message.text == content }?.let {
          groupManager.setNoticeId(it.noticeId.toInt())
        }
      }
    }

    register("登记荒野") {
      val groupManager = event.getGroupManager() as GroupManager
      if (groupManager.getAdminOnly() && (event.sender?.role !== Role.ADMIN && event.sender?.role !== Role.OWNER)) {
        napcatAPI.sendGroupMsg(event.groupId!!, "权限不足！")
        return@register
      }
      if (args.isEmpty()) {
        napcatAPI.sendGroupMsg(event.groupId!!, "未提供session id！")
        return@register
      }
      val sessionId = args[0]
      groupManager.addWildsSession(sessionId)
      napcatAPI.sendGroupMsg(event.groupId!!, "登记成功！")
      if (groupManager.getNoticeEnabled()) {
        groupManager.getNoticeId()?.let {
          napcatAPI.delGroupNotice(event.groupId, it)
        }
        val date = LocalDate.now()
        napcatAPI.sendGroupNotice(event.groupId, "${date.month}月${date.dayOfMonth}日集会：\n${groupManager.getSessionString()}")
      }
    }

    register("删除世界") {
      val groupManager = event.getGroupManager() as GroupManager
      if (groupManager.getAdminOnly() && (event.sender?.role !== Role.ADMIN && event.sender?.role !== Role.OWNER)) {
        napcatAPI.sendGroupMsg(event.groupId!!, "权限不足！")
        return@register
      }
      if (args.isEmpty()) {
        napcatAPI.sendGroupMsg(event.groupId!!, "未提供session id！")
        return@register
      }
      val sessionId = args[0]
      groupManager.removeWorldSessionById(sessionId)
      napcatAPI.sendGroupMsg(event.groupId!!, "删除成功！")
    }

    register("删除荒野") {
      val groupManager = event.getGroupManager() as GroupManager
      if (groupManager.getAdminOnly() && (event.sender?.role !== Role.ADMIN && event.sender?.role !== Role.OWNER)) {
        napcatAPI.sendGroupMsg(event.groupId!!, "权限不足！")
        return@register
      }
      if (args.isEmpty()) {
        napcatAPI.sendGroupMsg(event.groupId!!, "未提供session id！")
        return@register
      }
      val sessionId = args[0]
      groupManager.removeWildsSessionById(sessionId)
      napcatAPI.sendGroupMsg(event.groupId!!, "删除成功！")
    }

    register("switchAdminOnly") {
      val groupManager = event.getGroupManager() as GroupManager
      if (event.sender?.role !== Role.ADMIN && event.sender?.role !== Role.OWNER) {
        napcatAPI.sendGroupMsg(event.groupId!!, "权限不足！")
        return@register
      }
      groupManager.switchAdminOnly()
      napcatAPI.sendGroupMsg(event.groupId!!, "切换成功，当前值：${groupManager.getAdminOnly()}")
    }

    register("switchNoticeEnabled") {
      val groupManager = event.getGroupManager() as GroupManager
      if (event.sender?.role !== Role.ADMIN && event.sender?.role !== Role.OWNER) {
        napcatAPI.sendGroupMsg(event.groupId!!, "权限不足！")
        return@register
      }
      groupManager.switchNoticeEnabled()
      napcatAPI.sendGroupMsg(event.groupId!!, "切换成功，当前值：${groupManager.getNoticeEnabled()}")
    }
  }
}


fun main(): Unit = runBlocking {
  System.setOut(java.io.PrintStream(System.out, true, "UTF-8"))
  val currentDir = Paths.get("").toAbsolutePath().toString()
  println("Application run at $currentDir")
  val dir = File("data")
  if (!dir.exists()) {
    dir.mkdirs()
  }
  val jsonStr = File("data/config.json").readText()
  val config = json.decodeFromString<Config>(jsonStr)
  val wsConfig = config.wsConfig
  val httpConfig = config.httpConfig
  val commandConfig = config.commandConfig
  val channel = Channel<String>()
  val napcatAPI = NapcatAPI(httpConfig)
  val parser = createCommand(commandConfig, napcatAPI)

  launch {
    println("Creating ws connection")
    val client = HttpClient(CIO) {
      install(WebSockets) {
        wsConfig.pingInterval?.let {
          pingInterval = it.milliseconds
        }
      }
    }

    client.webSocket(request = {
      url {
        protocol = if (wsConfig.ssl) {
          URLProtocol.WSS
        } else {
          URLProtocol.WS
        }
        host = wsConfig.host
        port = wsConfig.port
      }
      wsConfig.accessToken?.let {
        header("Authorization", "Bearer $it")
      }
    }) {
      for (frame in incoming) {
        when (frame) {
          is Frame.Text -> {
            val text = frame.readText()
            channel.send(text)
          }
          is Frame.Close -> {
            cancel()
          }
          is Frame.Ping -> send(Frame.Pong(frame.data))
          else -> {}
        }
      }
    }
    client.close()
  }

  launch {
    for (text in channel) {
      val event: Event = json.decodeFromString(text)
      when (event.postType) {
        PostType.MESSAGE -> {
          event.messageType?.let {
            println("Receive message: ${event.rawMessage}")
            if (it == MessageType.GROUP) {
              parser.parse(event.rawMessage!!, event)
            }
          }
        }
        else -> {}
      }
    }
  }

}


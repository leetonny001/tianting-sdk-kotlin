# 天听 SDK（Kotlin）

天听 IM 的 Android SDK：网络层与数据层（连接与重连、首帧鉴权、收发、同步、会话、未读、已读、本地缓存、好友与拉黑、群管理、封禁 / 删除 / 禁言、系统推送），不依赖任何 UI 框架。核心是纯 Kotlin/JVM，可以脱离 Android 跑测试。

- 服务端、协议、需求文档：[tianting-server](https://github.com/leetonny001/tianting-server)
- SDK 设计说明（三套 SDK 的行为基准）：tianting-server 的 `docs/sdk/README.md`
- 同一套行为规则的其他实现：[tianting-sdk-ts](https://github.com/leetonny001/tianting-sdk-ts)、[tianting-sdk-swift](https://github.com/leetonny001/tianting-sdk-swift)

## 模块

| 模块 | 内容 | 依赖 |
|---|---|---|
| `sdk-core` | 全部逻辑：协议模型、连接、发送队列、同步引擎、已读、社交、群、第二批事件、本地库的 SQL 和事务边界；测试替身 `com.tianting.sdk.testing` | 协程、kotlinx.serialization、OkHttp |
| `sdk-sqlite-jdbc` | `LocalDatabase` 的 JVM 实现（sqlite-jdbc）：测试、桌面 | sdk-core |
| `sdk-android` | `AndroidLocalDatabase`（系统 SQLite）、`AndroidPushCursorStore`（推送游标文件）、`AndroidPinyin`（android.icu）、`bindNetwork`（网络恢复立即重连） | sdk-core、Android SDK |

没有 Android SDK 的机器上 `sdk-android` 不参与构建，其他模块不受影响。

## 用法

```kotlin
val im = IMClient(IMClientOptions(
    url = "wss://im.example.com/ws",              // 不带 token
    platform = Platform.ANDROID,
    database = AndroidLocalDatabase.open(context), // 本地库；null 时只在内存
    isVisible = { lifecycle.isAtLeast(STARTED) },  // 已读上报依据；要线程安全
))
val unbind = im.bindNetwork(context)               // 网络恢复时立即重连
// 前后台切换时调 im.visibilityChanged()

im.events.onEach { e -> … }.launchIn(scope)        // 要在 connect 之前开始收集
im.conversations.onEach(render).launchIn(scope)
im.connect(token)

im.send(convId, text)                              // Ok(message) 或 Rejected(EMPTY | TOO_LONG, over)
im.retry(convId, clientMsgId)                      // 手动重发失败的消息
im.loadHistory(convId)                             // 向前翻页，返回是否还有更早的
im.setActiveConversation(convId)                   // 进入 / 离开聊天页（null）
im.disconnect()                                    // 退出登录：清内存、清本地库
im.close()                                         // 进程退出前：只释放资源，不清本地库
```

线程：SDK 内部状态只在一条线程上访问（行为与 sdk-ts 的事件循环一一对应）。`suspend` 方法可以从任意线程调，`StateFlow` 和 `messages(convId)` 可以从任意线程读。

### 接口

| 类别 | 接口 |
|---|---|
| 状态流 | `state`、`banner`、`conversations`、`totalUnread`、`pendingCount`、`friends`、`friendRequests`、`blocks`、`socialSettings`、`socialLoaded`、`isOfficial`、`fatal`、`truncated`、`userId`、`messagesFlow(convId)`、`messages(convId)` |
| 事件 | `events`：`MessagesChanged`、`SendFailed`、`ConvRemoved`、`MuteChanged`、`GroupChanged`、`Fatal` |
| 系统推送 | `pushes: Flow<Push>`、`pushSynced: Flow<Long>`、`pushCursor()`；选项 `pushStart`、`pushCursorStore`、`pushLimits`（见「系统推送」） |
| 连接 | `connect`、`disconnect`、`close`、`networkOnline`、`visibilityChanged`、`serverNow` |
| 消息 / 会话 | `send`、`retry`、`removeFailed`、`loadHistory`、`setActiveConversation`、`refreshConversations`、`conversationSnapshot`、`muteState`、`debugExport` |
| 社交 | `applyFriend`、`respondFriend`、`deleteFriend`、`setFriendRemark`、`markFriendRequestsSeen`、`relationOf`、`block`、`unblock`、`searchUser`、`setSearchable`、`getUsers`、`profileOf`、`displayName`、`loadMyGroups`、`refreshSocial` |
| 群 | `createGroup`、`inviteToGroup`、`leaveGroup`、`kickFromGroup`、`transferGroupOwner`、`setGroupAdmin`、`dissolveGroup`、`updateGroup`、`getGroupInfo`、`loadGroupMembers` |
| 工具 | `c2cConvId`、`groupConvId`、`parseConvIdStrict`、`peerId`、`checkText`、`trimText`、`countCodePoints`、`describeError`、`messageFor`、`tipText`、`ErrorCode`、`CloseCode`、`avatarColorIndex`、`avatarInitial`、`fnv1a32`、`pinyinGroup`、`groupByPinyin`、`compareNames` |
| 测试替身 | `com.tianting.sdk.testing`：`FakeTransport`、`FakeConnection`、`FakeClock` |

失败的命令抛 `IMError`（`code`、`userId`、`details` 透传）、`TimeoutError`、`NotConnectedError`、`ConnectionClosedError`、`ProtocolDecodeError`。

## 系统推送

规则见 PRD-live 4.3、4.5、4.8、4.9 节，三端一致的判据是 `protocol/vectors/push-sync.json`（71 个用例全部在 `PushVectorsTest` 里跑）。

```kotlin
val im = IMClient(IMClientOptions(url = url, database = db, pushCursorStore = AndroidPushCursorStore(context)))
scope.launch { im.pushes.collect { p -> handle(p) } }   // 带 pushSeq 的至少一次，按 pushId 幂等
scope.launch { im.pushSynced.collect { latestSeq -> … } } // 可选，不算订阅者
im.connect(token)
```

| 规则 | 说明 |
|---|---|
| 订阅者 | 只算 `pushes` 的收集者。没有收集者时不发 `pushSync`，带 `pushSeq` 的帧丢弃（之后补拉），不带的进上限 500 的队列 |
| 交付 | `onFrame` 不挂起。帧、ack、定时器、注册进同一条串行队列，由单独的协程按序处理；收集者处理完一条才交付下一条，一页交付完才前进游标、取下一页，SDK 线程不被占住 |
| 收集者跟不上 | 队列有界：带 `pushSeq` 的超过 200 条丢弃、空闲后补拉一轮；不带的超过 500 条丢最旧。收集者永远不返回时推送停在那里（其他功能不受影响），退出登录能打断 |
| 收集者抛错 | 只结束它自己，其他收集者继续，重新 `collect` 可以继续收 |
| 持久化 | `PushCursorStore` 独立于 `database`，互不依赖：退出、被踢、库重建、Schema 升级都不清，只存游标和最近 2000 个 `pushId`，一个事件处理完落盘一次。**没有注入时游标只在内存**，每个新实例从 `afterSeq=0` 回放，和 H5 相同；`database` 为 null 不影响 |
| 日志 | SDK 不写 `data`，`Push.toString()` 也不带它 |

## 本地持久化

方案：不用 Room，用一个很小的接口 `LocalDatabase`（`execute` / `query` / `transaction`），所有 SQL、表结构、事务边界都在 `sdk-core` 的 `LocalDb`；JVM 上由 sqlite-jdbc 实现，Android 上由系统 `SQLiteDatabase` 实现。这样 JVM 测试验证的就是 Android 上执行的同一份 SQL，核心模块保持纯 JVM。

| 规则 | 依据 |
|---|---|
| `socialVer` 与数据在同一个事务写入；增量轮逐页写、ver 在最后一页前进；全量轮收齐后一个事务整体替换 | PRD-social 10.2 节、FE-131 |
| 本地库写失败不影响收发：事务回滚、记日志（只写异常类型），内存状态继续有效，下次同步修复 | 实现约定 |
| 收到 `msgDeleted`：改写 `messages.json`、会话摘要，并记下已删除的 msgId / seq；之后到的 sync、ack 带着原文也写不回来 | FE-435、AC-ADM-31-4 |
| 数据库文件里搜不到原文：`secure_delete=ON`（不支持时每次清除后 `VACUUM`）、`journal_mode=DELETE`（不用 WAL）、提交后 `wal_checkpoint(TRUNCATE)` | FE-435 |
| `convRemoved`（含 convs 推断出来的）：删除本地库中该会话；快照只在内存，到本次登录结束 | FE-435、G-UI-21 |
| 被踢 / 退出登录 / 换账号：清库 | FE-131 |
| 冷启动：恢复会话、消息（每个会话最新 200 条，更早的删掉靠翻页）、社交状态；连上后从持久化的 ver 增量同步，并重新拉已加载的范围（离线期间的删除） | F-SOC-06 规则 10、F-ADM-31 规则 5 |
| 上次进程结束时还没发出去的消息：恢复为失败，不自动发出，可以手动重发（沿用原 `clientMsgId`） | 实现约定 |

`removeFailed(convId, clientMsgId)`（G-UI-14）：只对失败的消息有效，不发帧，`pendingCount` 减 1；正文从本地库清掉，并记下 `clientMsgId`，晚到的 ack、sync 和冷启动都不会让它再出现。

token 不进本地库。数据库文件放在应用私有目录。

## 开发

需要 JDK 21。Gradle 用项目内的 Wrapper。

```bash
./gradlew :sdk-core:test              # 单元测试（假传输层 + 假时钟 + 真 SQLite 文件）
./gradlew :sdk-core:check             # 上面 + ErrorCodes.gen.kt 与 protocol/errors.json 一致性
ANDROID_HOME=… ./gradlew :sdk-android:testDebugUnitTest          # Android 适配层（Robolectric 原生 SQLite）
ANDROID_HOME=… ./gradlew :sdk-android:connectedDebugAndroidTest  # 连着设备 / 模拟器：系统 SQLite、android.icu
```

## 协议副本

`protocol/` 是服务端仓库协议文件的副本（错误码、帧 Schema、共享测试用例），不要直接改。服务端协议变更后：

```bash
scripts/sync-protocol.sh       # 默认从同级目录 ../tianting-server（或 ../im-mvp）同步，并重新生成 ErrorCodes.gen.kt
```

错误码由 `./gradlew :sdk-core:generateErrors` 生成，`check` 任务和 `VectorsTest` 都会校验一致。SDK 发出的每一帧在单元测试里都会用 `frame.schema.json` 校验。

## 集成测试

集成测试会编译并启动真实的服务端（测试模式，`IM_V13_ADMIN_FEATURES=1`，推送用例另加 `IM_PUSH_ENABLED=1`），需要 Go 1.27+ 和 tianting-server 的源码：

```bash
./gradlew :sdk-core:integrationTest   # 含 PushIntegrationTest（IM_PUSH_ENABLED=1，另起一个关闭开关的服务端验 4001）。默认找同级目录 ../tianting-server/server 或 ../im-mvp/server
TIANTING_SERVER_DIR=/path/to/tianting-server/server ./gradlew :sdk-core:integrationTest
```

CI 里的集成测试需要读取私有仓库 tianting-server：配置只读部署密钥和 Actions secret `TIANTING_SERVER_DEPLOY_KEY`。没配置时 CI 跳过集成测试，只跑单元测试。

```bash
scripts/setup-ci-deploy-key.sh   # 由仓库所有者本人运行：生成密钥、添加只读 Deploy key、设置 secret、删除本地密钥
```

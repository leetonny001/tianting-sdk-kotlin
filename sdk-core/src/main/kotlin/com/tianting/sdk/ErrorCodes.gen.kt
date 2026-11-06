// 由 ./gradlew :sdk-core:generateErrors 从 protocol/errors.json 生成，不要手改。
package com.tianting.sdk

/** 错误码。 */
public object ErrorCode {
    public const val BAD_REQUEST: Int = 4000
    public const val UNKNOWN_CMD: Int = 4001
    public const val INVALID_FRAME: Int = 4002
    public const val MISSING_REQUEST_ID: Int = 4003
    public const val INVALID_ID: Int = 4004
    public const val TEXT_EMPTY: Int = 4005
    public const val TEXT_TOO_LONG: Int = 4006
    public const val INVALID_CONV_ID: Int = 4007
    public const val CLIENT_MSG_ID_CONFLICT: Int = 4008
    public const val INVALID_PLATFORM: Int = 4009
    public const val TOKEN_INVALID: Int = 4010
    public const val TOKEN_EXPIRED: Int = 4011
    public const val TOKEN_REVOKED: Int = 4012
    public const val ADMIN_KEY_INVALID: Int = 4013
    public const val ADMIN_SIGNATURE_INVALID: Int = 4017
    public const val ACCOUNT_CREDENTIALS_INVALID: Int = 4018
    public const val REFRESH_TOKEN_INVALID: Int = 4019
    public const val FILE_NOT_FOUND: Int = 4048
    public const val FORBIDDEN: Int = 4030
    public const val MUTED: Int = 4031
    public const val NOT_CONV_PARTICIPANT: Int = 4032
    public const val NOT_GROUP_MEMBER: Int = 4033
    public const val USER_BANNED: Int = 4034
    public const val NOT_FRIEND: Int = 4035
    public const val REJECTED_BY_PEER: Int = 4036
    public const val MSG_READ_GRANT_REQUIRED: Int = 4039
    public const val NOT_FOUND: Int = 4040
    public const val NOT_IN_ROOM: Int = 4041
    public const val USER_NOT_FOUND: Int = 4042
    public const val GROUP_NOT_FOUND: Int = 4043
    public const val MESSAGE_NOT_FOUND: Int = 4044
    public const val FRIEND_REQUEST_NOT_FOUND: Int = 4045
    public const val TARGET_NOT_GROUP_MEMBER: Int = 4046
    public const val GROUP_EXISTS: Int = 4090
    public const val FRIEND_REQUEST_HANDLED: Int = 4091
    public const val PUSH_ID_CONFLICT: Int = 4094
    public const val USERNAME_TAKEN: Int = 4095
    public const val GROUP_MEMBER_LIMIT_EXCEEDED: Int = 4130
    public const val BATCH_LIMIT_EXCEEDED: Int = 4131
    public const val FRIEND_LIMIT_EXCEEDED: Int = 4132
    public const val PEER_FRIEND_LIMIT_EXCEEDED: Int = 4133
    public const val BLOCK_LIMIT_EXCEEDED: Int = 4134
    public const val JOINED_GROUP_LIMIT_EXCEEDED: Int = 4135
    public const val GROUP_ADMIN_LIMIT_EXCEEDED: Int = 4136
    public const val STRANGER_MSG_LIMIT: Int = 4137
    public const val RATE_LIMITED: Int = 4290
    public const val QUOTA_EXCEEDED: Int = 4292
    public const val ACCOUNT_LOGIN_LOCKED: Int = 4294
    public const val INTERNAL: Int = 5000
    public const val SERVICE_UNAVAILABLE: Int = 5030
}

/** 错误码的详细信息：是否可以重试、给开发者的说明。 */
internal val ERROR_INFO: Map<Int, ErrorInfo> = mapOf(
    4000 to ErrorInfo("BAD_REQUEST", false, "参数不合法"),
    4001 to ErrorInfo("UNKNOWN_CMD", false, "未知命令"),
    4002 to ErrorInfo("INVALID_FRAME", false, "帧格式错误"),
    4003 to ErrorInfo("MISSING_REQUEST_ID", false, "请求缺少 id"),
    4004 to ErrorInfo("INVALID_ID", false, "ID 格式不合法"),
    4005 to ErrorInfo("TEXT_EMPTY", false, "消息内容为空"),
    4006 to ErrorInfo("TEXT_TOO_LONG", false, "消息超过 5000 字"),
    4007 to ErrorInfo("INVALID_CONV_ID", false, "会话 ID 不合法"),
    4008 to ErrorInfo("CLIENT_MSG_ID_CONFLICT", false, "clientMsgId 已用于其他会话"),
    4009 to ErrorInfo("INVALID_PLATFORM", false, "平台标识不合法"),
    4010 to ErrorInfo("TOKEN_INVALID", false, "登录凭证无效"),
    4011 to ErrorInfo("TOKEN_EXPIRED", false, "登录凭证已过期"),
    4012 to ErrorInfo("TOKEN_REVOKED", false, "账号已被强制下线"),
    4013 to ErrorInfo("ADMIN_KEY_INVALID", false, "管理密钥无效"),
    4017 to ErrorInfo("ADMIN_SIGNATURE_INVALID", false, "管理接口签名无效"),
    4018 to ErrorInfo("ACCOUNT_CREDENTIALS_INVALID", false, "用户名、密码或恢复码错误（不区分）"),
    4019 to ErrorInfo("REFRESH_TOKEN_INVALID", false, "refresh token 无效、过期、吊销或被重放"),
    4048 to ErrorInfo("FILE_NOT_FOUND", false, "文件不存在、无权访问、已过期或已撤回（不区分）"),
    4030 to ErrorInfo("FORBIDDEN", false, "无权限"),
    4031 to ErrorInfo("MUTED", false, "被禁言，不能发言"),
    4032 to ErrorInfo("NOT_CONV_PARTICIPANT", false, "不是该会话的参与者"),
    4033 to ErrorInfo("NOT_GROUP_MEMBER", false, "不是群成员"),
    4034 to ErrorInfo("USER_BANNED", false, "用户被封禁，不能签发 token / 演示登录 / 账号登录（details.bannedUntil，永久为 null）"),
    4035 to ErrorInfo("NOT_FRIEND", false, "不是好友"),
    4036 to ErrorInfo("REJECTED_BY_PEER", false, "消息被对方拒收"),
    4039 to ErrorInfo("MSG_READ_GRANT_REQUIRED", false, "没有该会话有效的正文 / 隔离原文查看授权"),
    4040 to ErrorInfo("NOT_FOUND", false, "资源不存在"),
    4041 to ErrorInfo("NOT_IN_ROOM", false, "未加入该聊天室"),
    4042 to ErrorInfo("USER_NOT_FOUND", false, "用户不存在"),
    4043 to ErrorInfo("GROUP_NOT_FOUND", false, "群不存在"),
    4044 to ErrorInfo("MESSAGE_NOT_FOUND", false, "消息不存在（或隔离原文已过期删除）"),
    4045 to ErrorInfo("FRIEND_REQUEST_NOT_FOUND", false, "好友申请不存在或已过期"),
    4046 to ErrorInfo("TARGET_NOT_GROUP_MEMBER", false, "操作对象不是群成员"),
    4090 to ErrorInfo("GROUP_EXISTS", false, "群 ID 已存在"),
    4091 to ErrorInfo("FRIEND_REQUEST_HANDLED", false, "申请已同意"),
    4094 to ErrorInfo("PUSH_ID_CONFLICT", false, "同一 pushId 的调用方或内容不同"),
    4095 to ErrorInfo("USERNAME_TAKEN", false, "用户名已存在或保留"),
    4130 to ErrorInfo("GROUP_MEMBER_LIMIT_EXCEEDED", false, "群成员已达上限"),
    4131 to ErrorInfo("BATCH_LIMIT_EXCEEDED", false, "单次批量操作数量超限"),
    4132 to ErrorInfo("FRIEND_LIMIT_EXCEEDED", false, "好友数量已达上限"),
    4133 to ErrorInfo("PEER_FRIEND_LIMIT_EXCEEDED", false, "对方好友数量已达上限"),
    4134 to ErrorInfo("BLOCK_LIMIT_EXCEEDED", false, "黑名单已满"),
    4135 to ErrorInfo("JOINED_GROUP_LIMIT_EXCEEDED", false, "加入的群聊已达上限"),
    4136 to ErrorInfo("GROUP_ADMIN_LIMIT_EXCEEDED", false, "管理员已满 10 人"),
    4137 to ErrorInfo("STRANGER_MSG_LIMIT", false, "对方回复前的条数已用完"),
    4290 to ErrorInfo("RATE_LIMITED", true, "操作过于频繁"),
    4292 to ErrorInfo("QUOTA_EXCEEDED", false, "今天的次数已用完"),
    4294 to ErrorInfo("ACCOUNT_LOGIN_LOCKED", false, "尝试过多，暂时锁定（details.retryAfterSeconds）"),
    5000 to ErrorInfo("INTERNAL", true, "服务器内部错误"),
    5030 to ErrorInfo("SERVICE_UNAVAILABLE", true, "服务暂不可用"),
)

/** client scope 的错误码：账号和文件的 HTTP 接口会返回（PRD-chat 第 11.5 节，F-SYS-01）。 */
public val CLIENT_ERROR_CODES: Set<Int> = setOf(
    4000,
    4004,
    4010,
    4011,
    4012,
    4018,
    4019,
    4048,
    4030,
    4034,
    4095,
    4290,
    4292,
    4294,
    5000,
    5030,
)

/** 错误码 → 细分的 reason（PRD-chat 第 11.5 节）。收到没有列出的 reason 不能报错。 */
public val ERROR_REASONS: Map<Int, List<String>> = mapOf(
    4000 to listOf("passwordWeak", "recoveryCodeStale"),
    4011 to listOf("tokenSuperseded"),
    4019 to listOf("invalid", "expired", "revoked", "superseded", "replayDetected", "credentialsChanged", "loggedOutOthers", "accountDeleted"),
    4030 to listOf("registerClosed", "inviteInvalid", "accountSessionRequired", "accountExists", "accountDeletionPending", "featureDisabled"),
    4290 to listOf("recoverLimited"),
)

/** WebSocket 关闭码。 */
public object CloseCode {
    public const val NORMAL: Int = 1000
    public const val GOING_AWAY: Int = 1001
    public const val MESSAGE_TOO_BIG: Int = 1009
    public const val INTERNAL_ERROR: Int = 1011
    public const val INVALID_PLATFORM: Int = 4009
    public const val TOKEN_INVALID: Int = 4010
    public const val TOKEN_EXPIRED: Int = 4011
    public const val TOKEN_REVOKED: Int = 4012
    public const val SLOW_CONSUMER: Int = 4400
    public const val AUTH_REQUIRED: Int = 4401
    public const val IDLE_TIMEOUT: Int = 4408
    public const val TOO_MANY_CONNECTIONS: Int = 4429
}

/** 关闭后是否应该自动重连（未列出的关闭码按可以重连处理）。 */
internal val CLOSE_RECONNECT: Map<Int, Boolean> = mapOf(
    1000 to false,
    1001 to true,
    1009 to true,
    1011 to true,
    4009 to false,
    4010 to false,
    4011 to false,
    4012 to false,
    4400 to true,
    4401 to true,
    4408 to true,
    4429 to false,
)

/** 被踢原因 → 关闭码（null 表示保留值，一期不出现）。 */
internal val KICK_CLOSE_CODE: Map<String, Int?> = mapOf(
    "tokenInvalid" to 4010,
    "tokenExpired" to 4011,
    "banned" to 4012,
    "suspended" to 4012,
    "passwordChanged" to 4012,
    "sessionRevoked" to 4012,
    "accountDeleted" to 4012,
    "tooManyConnections" to 4429,
    "serverShutdown" to 1001,
    "replaced" to null,
)

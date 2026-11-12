# 通用设备通道 · 三方对拍遗留项交接

> 交接人:原实现者
> 分支:`local-dev`(至 `07576df`,未 push)
> 日期:2026-11-12
> 预估总量:全部遗留项约 2–3 人日

> **处置状态(2026-11-12 第二轮)**:下列清单已按 P1–P2 推进一轮并全量自检通过(协议门禁 18/18 + typecheck + 安卓双构建)。
> - **已修**:§2 配对成功桌面通知、§3 设备端自助吊销(协议端点+设置页入口)、§4 会话页 personaName/lastMessage + 归档操作、§5 启停均失效 token 缓存、§7 busy 409 文案统一、§8 回合级流未知 turnId 404、§9 Origin 端口校验、§10 firstRun 过滤成员会话、§12 单会话 GET 忽略 query、§13 ChatScreen 稳定 key + delta 合帧、§15 FilesScreen(前轮已删)、§16 模型链(前轮已统一)、§四 文档(personaId/workspaceRoot 标注 v2、无归属 notice 留白记录)。
> - **仍留白(v2 或接受现状)**:§11 无归属 notice(v1 无影响,协议留白)、§14 Coil 迁移(可选,LruCache 已够)。
> - **§6 白烧码已修(第三轮)**:消费保持先行(不改并发语义),setSecret 失败时 `restorePairCode` 按原有效期恢复码(仅当期间桌面未再出新码)——设备同码重试即得,无交接文档担心的并发竞态。验证边界:恢复路径需 safeStorage 失败才触发,门禁无法模拟;逻辑为两行守卫(typecheck+全量门禁回归覆盖消费链路)。
> - 门禁脚本扩展至 18 项(⑥–⑭ 为本轮新增);修复了脚本自身的两处取码缺陷(双重 `.code`、§③b 漏 await 导致悬空出码顶替并发码)。
> - **真机回归(PryFox VM,同日)**:新 APK(md5 对账一致)重配后全链通过——深链预填重配、会话列表 lastMessage/摘要行、慢流回合流式渲染(delta 合帧后不回退)、done 收尾(停止→发送按钮切换)、长按归档/取消归档 + 已归档徽标、设置页「吊销本设备」→ App 回未配对 + 桌面设备列表同步移除。
> - **真机观察(既有,非本轮回归)→ 第三轮已修**:①本机发送双用户气泡竞态(turn_start 先于 send 响应)→ `pendingSendText` 在途标记修复,真机确认单气泡;②EventStream 看门狗 sleep 中断的 InterruptedException 噪音日志 → 已包 InterruptedException 吞掉。
> - **第三轮(附件)**:§1 手机发送附件已落 v1——`POST /api/attachments?name=`(≤24MB,与桌面粘贴同一管道,设备端只交字节不交路径不变式不变)+ 安卓选图上传/待发 chip/send 带 attachments[](capability `attachments`,门禁扩至 22 项,真机全链通过)。**真机坑**:photo picker 部分条目 openInputStream ENOENT,已吞 IOException 走 snackbar。

## 背景

通用设备(GeneralDevice)入站通道已完整实现并通过三方对拍(文档 / 桌面服务端 / 手机客户端)、5-agent 审查、真机回归(全绿)。本文档列出审查与对拍中**发现但未修复**的遗留项,供后续同事拾起。

已完成并验证的部分不再赘述,详见 `git log --oneline` 最近 15 个 commit 与 `llmdoc/channels/general-device-*.mdx` 文档集。

---

## 未修项清单

### 一、功能缺口(建议优先)

#### 1. 手机发送附件

- **现状**:协议 `POST /api/sessions/:id/send` 支持 `attachments[]`(string[]),手机 UI 和 DevaClient 只发 `text`,无附件入口。
- **根因**:附件需要先上传到桌面(协议无上传端点),`POST /api/attachments` 在设计文档标注为 **v2 预留**。
- **修法**:桌面新增上传端点(接收文件字节 → 落盘到临时区 → 返回服务端路径),手机端拍照/相册选图 → 上传 → 拿到路径 → 填入 send 的 `attachments[]`。
- **工作量**:M(桌面端点 + 手机 UI + 权限/压缩)

#### 2. 配对成功桌面提醒

- **现状**:新设备配对成功后,桌面无任何通知——用户不知道有新设备接入。
- **修法**:`server.ts` 配对成功路径调桌面通知(对齐 IM bot 配对成功的通知模式,`remote/index.ts` 有先例可查)。
- **工作量**:S

#### 3. 设备端自助吊销

- **现状**:token 泄漏唯一恢复路径是物理回到桌面操作吊销。
- **修法**:新增 `POST /api/devices/self` (凭 Bearer token 自吊销):服务端按 token 反查 deviceId → 走 removeDevice 全套清理 → 返回 `{ok:true}`。安全正面(丢失设备可远程自救,前提是攻击者尚未更改凭据)。
- **工作量**:S

#### 4. 会话页信息补齐

- **现状**:会话页缺角色徽标(personaId 在响应里但 UI 不展示)、缺最后消息摘要(需桌面 `/api/sessions` 加 `lastMessage` 字段或手机端按需拉取)。archived 徽标已做,但无归档/取消归档操作。
- **工作量**:S–M

### 二、正确性 / 加固

#### 5. 停用设备不失效 token 缓存(已修但边界残留)

- **现状**:`general:set-device-enabled(false)` 已调 `invalidateTokenCache()` + `closeDeviceConnections(id)` ✅。但 `setDeviceEnabled` **不**失效缓存——仅在 `enabled=false` 分支才调。若后续有批量启停或其他调用方,需确保同样调 invalidateTokenCache。
- **另一个竞态窗口**:`setDeviceEnabled` 掐断 SSE 与建连竞态——`closeDeviceConnections` destroy 与客户端自动重连之间可能重建(客户端有 45s 看门狗 + 退避,最坏 30s 后重连成功但鉴权已短路 → 401 → 正确拒绝)。行为正确但可能产生短暂错误日志噪音。

#### 6. 配对成功后密钥存储失败白烧码

- **现状**:配对路由先 `consumePairCodeIfMatch(code)`(原子消费)再 `await setSecret(...)`。setSecret 失败 → 500,码已作废,设备需重新获取新码。
- **影响**:低频(仅在 safeStorage 不可用时),且桌面重新出码即恢复。可接受,但如果要优化:将消费移到 setSecret 成功之后(需评估并发窗口)。
- **工作量**:XS(顺序调换有并发竞态风险,需权衡)

#### 7. busy→insert 检查两次文案不同

- **现状**:`/send` 路由中 `rt.isBusy(sid)` 检查出现两次(读 attachments 前后各一次,防竞态),两处 409 错误文案不同:「请稍后再试。」vs「发送 /stop 可以停止它。」
- **建议**:统一文案,或第一次检查后直接走 insert(与 hub 同口径)。

#### 8. turn 级 SSE 对未知 turnId 也开流

- **现状**:`GET /api/turns/:id/events` 无 turnId 存在性校验,未知 turnId 照常 200 开流空等。
- **影响**:客户端 bug 导致的无效连接不被拒绝,占用配额(64/4)直到客户端主动断。
- **修法**:校验 `turnIndex.has(turnId)`(有则允许,无则 404)。注意新回合可能尚未入 ring(turn_start 未发),需配合「回合级流仅兼容」的定位评估是否值得加。

#### 9. Origin 不校验端口

- **现状**:Origin 校验只比 hostname,不比端口。
- **影响**:本机 4317 端口上的其他服务可伪造 Origin。
- **修法**:Origin 端口也需匹配(4317 或空)。XS。

#### 10. firstRun 口径含成员会话

- **现状**:`/api/info` 的 `firstRun = listSessions().length === 0`,不排除 parentId 成员会话;而 `/api/sessions` 列表过滤成员会话。
- **影响**:`firstRun` 语义不一致(列表空但 firstRun=false 的矛盾状态可出现)。
- **修法**:`firstRun` 改为过滤 parentId 后的列表长度判断。XS。

#### 11. notice 无 sessionId 时对所有 SSE 连接不可达

- **现状**:无 sessionId 的 notice 不入 ring,且会话级连接匹配要求 `f.sessionId === conn.sessionId`(undefined ≠ 任何值),回合级要求锁定后 `f.sessionId` 相等——无归属通知实际只被 chat-bus 的非 SSE 订阅者消费。
- **影响**:设备端永远收不到无归属通知(如果未来有此类通知需求,需扩展协议)。
- **v1 无影响**(当前所有通知都带 sessionId)。

#### 12. GET /api/sessions/:id 带 query 落 404

- **现状**:路由显式要求 `!url.search`,带任意 query 不命中 → 落入兜底 404。
- **建议**:忽略 query 或返回 400 带明确文案。XS。

### 三、性能 / 代码质量

#### 13. ChatScreen 渲染优化(部分完成)

- **已完成**:近底部才自动滚动 ✅、items 加载更早头部 ✅。
- **未做**:每个 SSE 帧(可达 20-60 帧/s)做一次 O(n) 列表拷贝 + StateFlow 发射 + LazyColumn 全列表 diff;`items(items)` 无稳定 key(位置同一性);text_delta 未合帧。
- **量化**:n≈400 时每帧拷贝 ~3KB、30 帧/s ≈ 0.1MB/s 拷贝——现代设备 CPU 占比 <3%,真实代价是长回合中滚动动画与阅读位置被拽。
- **建议**:`items(items, key = { it::class.simpleName + it.hashCode() })` 或增量 List 操作;text_delta 合帧节流到 ~30fps。S–M。

#### 14. 图片预览 Coil 迁移(可选)

- **现状**:LruCache 最小方案已够(48MB 字节预算),EXIF 已修正。Coil 可再简化但非必要。
- **工作量**:XS–S

#### 15. FilesScreen 空壳

- **现状**:`ui/FilesScreen.kt` 是空壳 TODO,导航无路由引用。交付卡已在 ChatScreen 内覆盖 §5-5 需求。
- **建议**:删除文件(减少困惑),或在设计文档注明已由对话内交付卡替代。

#### 16. 模型解析链已统一(hub + channel-server 同源)

- **已完成**:✅ `resolveModelRefForSend` 供 hub 与 channel-server 调同一份。

### 四、文档精确度(部分完成)

- **已完成**:错误码约定表、busy→insert 行为、members 过滤、模型链统一、SSE 治理数值、超出设计记录小节——全部已回写 ✅。
- **未做**:`POST /api/sessions` 设计表写可选 `personaId / workspaceRoot`,实现两者均未收(桌面端建会话也不传)——设计文档应修订或标注 v2。

---

## 验证方法

```bash
# 协议门禁(需起隔离桌面实例 + mock 模型,用法见脚本头注释)
node apps/desktop/scripts/general-device-gate.mjs [CDP端口]

# 类型检查
pnpm typecheck

# 安卓构建
cd apps/android && JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:assembleDebug :app:assembleRelease
```

## 注意事项 / 坑

1. **gradle 守护进程 VFS 假构建**:改完源码后 gradle 可能报 UP-TO-DATE 但实际没编译——必见 `compileDebugKotlin` 非 UP-TO-DATE;装到设备后用 `adb shell md5sum <pm path 返回的 base.apk>` 与本地 `md5 -q` 对账。
2. **uiautomator dump 失败静默留旧文件**:先 `rm /sdcard/ui.xml` 再 dump;dump 失败时读旧文件会误判屏幕状态。
3. **`text="[^"]{1,N}"` 正则会整条滤掉超长文本**:先看 len 再截断。
4. **adb shell 命令中 `&` 会截断 URI**:深链测试时 URI 需在设备侧加引号。
5. **gradle --stop 后 packageDebugResources 可能瞬时失败**:重跑即好。

---

## 已完成项(仅供确认,不需要重做)

- P0 八项:mount 强制 null、重名不烧码、stream_reset 残尾、pairRequest 一次性、离开作废码、send 409 口径、畸形编码、FileSaver IS_PENDING
- P1 八项:EventStream 代际号/看门狗/续传作用域、ring 治理、写背压、引用计数、限速熔断、多候选地址
- P2:会话级 SSE(验收线 4)、协议版本化、单设备启停/改名、事件最小映射、加载更早+archived、Origin 校验、图片 LruCache+EXIF、深链预填、推送、模型链统一
- 真机:PryFox VM 安装/启动/配对/流式/**桌面回合实时可见**/深链预填/错误态/深浅主题,FATAL 0
- 文档:零知识文档集 5 篇(llmdoc 台账注册)+ 设计文档全部回写

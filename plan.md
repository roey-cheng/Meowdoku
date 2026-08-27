# Meowdoku 现场公网版本计划

## Summary

- 保留现有 `GameBoard`、`generateSolution()`、区域扩张和 BFS。
- 玩家点击开始时，由服务器在当前 HTTP 请求中实时生成棋盘。
- 移除全局唯一的 `ActiveGame`，改成每个浏览器 Cookie 会话拥有独立游戏。
- 支持 4×4–9×9、刷新恢复、20–50 人并发和手机触控。
- 使用单个付费 Render 实例部署一周，通过 Cloudflare 域名访问。

## 1. 独立游戏会话

在 `WebMain` 中增加会话存储：

```text
ConcurrentHashMap<sessionId, GameSession>
```

每个 `GameSession` 保存 `BrowserPlayer`、`MeowdokuGame`、最后访问时间和一个稳定的会话锁。

- Session ID 使用 `SecureRandom` 生成 32 字节随机值。
- Cookie 名称为 `meowdoku_session`。
- Cookie 设置 `Path=/; HttpOnly; SameSite=Lax; Max-Age=28800`，公网环境增加 `Secure`。
- 使用 8 小时滑动过期；API 成功访问时刷新有效期。
- 最多保存 500 个 session；创建新 session 时清理过期项。
- 达到上限且没有过期项时，新玩家收到 `503`，现有玩家不受影响。
- Cookie 缺失、伪造或过期时，读取游戏和猜测返回 `404`。
- 静态资源和 `/health` 不创建 session。
- 同一浏览器的普通标签页共享游戏；隐私窗口、其他浏览器和其他设备拥有不同游戏。
- Render 重启后内存游戏会消失，前端返回开局页面。

同一 session 的开局、读取和猜测使用同一把锁串行执行；不同 session 可以并行，避免 `BrowserPlayer.nextGuess` 被覆盖。

## 2. HTTP API 与前端

### API

- `POST /api/game?size=n`：接受 4–9，同步创建并启动游戏，成功后才替换旧游戏；非法尺寸或生成失败不得破坏旧游戏；返回 `200`，不使用后台任务或 `202`。
- `GET /api/game`：根据 Cookie 恢复当前可见状态，没有有效游戏时返回 `404`。
- `POST /api/guess?row=r&column=c`：只操作当前 Cookie 对应的游戏，保留现有 `BrowserPlayer.setNextGuess()`、`makeGuess()` 和 `playTurn()` 流程。

所有 JSON 继续只包含可见棋盘状态，绝不返回隐藏 `solution` 或 session ID。

服务器增加：

- 16 个线程、128 个排队任务的有界执行器。
- Shutdown hook，关闭 HTTP server 和执行器。
- `PUBLIC_ORIGIN` 精确检查公网写请求，不开放 CORS。
- `Content-Security-Policy`、`X-Content-Type-Options`、禁止 iframe 等基础安全头。
- API 保持 `Cache-Control: no-store`。
- 未预料异常返回通用 `500` JSON；日志不记录 Cookie 或完整 session ID。

### 前端

- 页面加载时调用 `GET /api/game`：`200` 恢复棋盘、分数、生命和已确认格子；`404` 显示尺寸选择页面，不自动创建新游戏。
- 开局期间显示 “Generating puzzle…” 并阻止重复提交。
- 保留单击标记、同格双击猜猫：单击等待 280ms 后切换标记，280ms 内同格第二次点击取消标记并只提交一次猜测；Space 标记，Enter 猜猫。
- 本地排除标记不上传服务器，刷新后清空。
- 保留 `touch-action: manipulation`，防止手机双击缩放。

## 3. 测试与验收

### 自动测试

- 使用两个独立 Cookie 客户端验证 A 的 4×4 和 B 的 9×9 互不覆盖。
- 验证同一 Cookie 刷新后，尺寸、分数、生命、猫和错误格子保持一致。
- 验证无效、伪造、过期 Cookie 返回 `404`。
- 验证同一 session 的两个并发猜测被正确串行处理。
- 验证 500 session 上限、8 小时过期和 Cookie 安全属性。
- 对 4–9 重复生成并验证每行/列一只猫、猫不接触、区域 ID 合法、所有格子已分配、每个区域存在且包含一只猫。
- JavaScript 测试覆盖单击、双击、不同格快速点击、busy/game-over 和键盘输入。
- 保留并运行所有现有 Java 与 JavaScript 测试。

### 并发烟测

- 新增独立 Java 烟测程序，使用 50 个独立 `CookieManager`。
- 每个用户同步开局、GET 恢复、猜测一次、再次 GET，连续运行两轮。
- 验收：50/50 成功；无跨 session 污染；无意外 `5xx`、JSON 错误或超时；预热后开局 p95 ≤ 5 秒、最大 ≤ 10 秒，猜测 p95 ≤ 2 秒。
- 使用 Android Chrome 和 iOS Safari 分别测试 4×4、9×9：单击只标记、双击只猜一次、不缩放页面。

## 4. Render 与 Cloudflare

新增 Java 17 多阶段 `Dockerfile`、`.dockerignore` 和 `render.yaml`：

- Docker 构建阶段编译 Java，运行阶段复制 class 与 `web/`。
- 服务读取 Render 提供的 `PORT` 并绑定 `0.0.0.0`。
- Render 使用 `starter` 付费实例、`singapore` 区域、单实例。
- 健康检查路径为 `/health`。
- `autoDeployTrigger: off`，活动期间只手动部署已测试版本。
- 设置 `SESSION_COOKIE_SECURE=true` 和最终的 `PUBLIC_ORIGIN`。

Render 配置参考 [Blueprint 文档](https://render.com/docs/blueprint-spec)、[Web Service 文档](https://render.com/docs/web-services)和[健康检查文档](https://render.com/docs/health-checks)。

Cloudflare：先在 Render 添加活动子域名；创建指向 Render 子域名的 Proxied CNAME；SSL/TLS 使用 Full (strict)；`/api/*` 不缓存。配置参考 [Cloudflare 的 Render 指南](https://developers.cloudflare.com/cloudflare-for-platforms/cloudflare-for-saas/saas-customers/provider-guides/render/)。

活动前 30 分钟从正式域名检查 `/health`，分别创建一次 4×4–9×9 游戏，运行两轮 50 用户烟测并完成两台真机触控测试。保留上一个成功 Render deploy 作为回滚版本。活动结束后删除或降级付费服务并清理 DNS。

## Assumptions

- 本次保留现有实时生成算法，不增加唯一解求解器，因此不承诺每个颜色棋盘只有一个逻辑答案。
- Session 隔离只解决不同玩家互相覆盖的问题。
- 不加入数据库、账号、排行榜或永久存档。
- 只运行一个 Render 实例；水平扩容前必须增加共享 session 存储。
- 控制台入口 `Main` 保持不变。
- README 记录本地运行、Docker、部署、烟测、session 行为和上述限制。

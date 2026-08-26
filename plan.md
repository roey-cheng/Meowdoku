# Meowdoku Java 网页版实施计划

> **For agentic workers:** 实施时按任务顺序执行，使用测试驱动开发，并在每个阶段重新运行完整测试。

**Goal:** 保留现有 Java 游戏结构和控制台入口，新增可在浏览器中游玩的 `4×4` 至 `9×9` Meowdoku。

**Architecture:** Java 继续负责 solution、区域、提示、猜测判定和计分。JDK 自带的 `HttpServer` 同源提供网页与 HTTP API；浏览器负责彩色棋盘、交互和本地排除标记。网页输入通过 `BrowserPlayer.makeGuess()` 接入现有 `Player` 多态结构。

**Tech Stack:** Java 17、JDK `HttpServer`、HTML、CSS、原生 JavaScript；不添加第三方依赖。

## Summary

- 运行 `WebMain` 后访问 `http://localhost:8080`。
- 保留原来的 `Main` 控制台玩法。
- 网页采用温暖猫咪手账风格，并支持手机和键盘操作。
- 第一阶段只实现本地单人网页；Docker、Render、自定义域名和公网多访客会话留到后续阶段。

## Task 1: 复用现有游戏回合

### BrowserPlayer

- 新增 `BrowserPlayer extends Player`。
- `setNextGuess(Position)` 保存 HTTP 收到的下一次猜测。
- `makeGuess()` 返回并清除该猜测。
- 没有待处理猜测时调用 `makeGuess()`，抛出明确的 `IllegalStateException`。

网页回合流程：

```text
浏览器点击格子
→ HTTP 创建 Position
→ BrowserPlayer.setNextGuess(position)
→ MeowdokuGame.playTurn()
→ BrowserPlayer.makeGuess()
→ GameBoard.checkGuess()
→ Player.recordGuess()
```

### MeowdokuGame

- 增加 `start()`：只在第一次调用时揭示免费猫并调用 `recordRevealedCat()`；重复调用不重复计数。
- 增加 `playTurn()`：调用 `player.makeGuess()`、`board.checkGuess()` 和 `player.recordGuess()`，返回 `GuessResult`。
- 增加 `isComplete()`。
- 控制台 `play()` 改为复用 `start()` 和 `playTurn()`。

### 只读状态接口

- `Player` 增加 `getGuesses()` 和 `getCatsFound()`。
- `GameBoard` 增加 `getSize()`、`getRegionId(row, column)` 和 `getCellState(row, column)`。
- 不增加任何可读取隐藏猫位置或完整 `solution` 的接口。

## Task 2: Java 网页服务

- 新增 `WebMain`，使用 `com.sun.net.httpserver.HttpServer`。
- 本地默认监听 `127.0.0.1:8080`。
- 如果存在 `PORT` 环境变量，则监听该端口并绑定 `0.0.0.0`，为后续公网部署预留。
- 固定提供 `web/index.html`、`web/styles.css` 和 `web/app.js`；不接受任意文件路径。
- 第一阶段服务器只维护一盘当前游戏。刷新页面可恢复当前服务器状态，Java 进程重启后重新开始。

### HTTP API

#### `POST /api/game?size=5`

- 只接受整数 `4–9`。
- 创建 `BrowserPlayer` 和 `MeowdokuGame`。
- 调用 `game.start()`。
- 返回完整的可见游戏状态。

#### `GET /api/game`

- 返回当前可见游戏状态。
- 没有当前游戏时返回 `404` JSON。

#### `POST /api/guess?row=2&column=3`

- 验证行列为整数并位于棋盘范围内。
- 调用 `browserPlayer.setNextGuess(new Position(row, column))`。
- 调用 `game.playTurn()`。
- 返回猜测结果和更新后的游戏状态。

#### `GET /health`

- 返回 `200` 和简单健康状态。

### JSON 状态

响应包含：

- `size`
- `score`
- `guesses`
- `catsFound`
- `complete`
- `message`
- `board`：每格只包含 `regionId` 和可见 `CellState`

响应不能包含 `solution` 或隐藏猫位置。

## Task 3: 网页 UI

### 页面结构

- 尺寸选择 `4–9`。
- `Start New Game` 和重新开始按钮。
- 分数、猜测次数和已找到猫数量。
- 简短规则说明。
- 响应式正方形棋盘。
- 游戏完成成绩卡。

### 棋盘显示

- 九个 `regionId` 映射到九种柔和颜色。
- 根据相邻格子的 `regionId` 添加粗区域边界，不能只依靠颜色区分。
- 已找到或免费揭示的猫显示 `🐱`。
- 服务器确认的错误猜测显示红色 `×`。

### 操作模式

- `Guess Cat`：点击格子后调用 `/api/guess`。
- `Mark Empty`：只在浏览器中切换灰色 `×`，不发送请求、不扣分。
- 本地标记使用 `row,column` 作为键。
- 对已被服务器判定的格子清除本地标记。
- 开始新游戏时清除全部本地标记。

### 可用性

- 每个格子使用 `<button>`。
- 支持 Enter、Space、清晰焦点样式和描述性 `aria-label`。
- 正确猜测使用轻微弹跳，错误猜测使用轻微摇动。
- 遵守 `prefers-reduced-motion`。
- 手机宽度下棋盘不得横向溢出。

## Task 4: 测试与验收

### Java 单元测试

- 保留并运行所有现有测试。
- 验证 `BrowserPlayer.setNextGuess()` 后，`makeGuess()` 返回同一个 `Position` 并清除待处理状态。
- 验证没有待处理猜测时 `makeGuess()` 抛出 `IllegalStateException`。
- 验证控制台玩家和 `BrowserPlayer` 都通过 `playTurn()` 完成判定与计分。
- 验证 `start()` 重复调用不会重复揭示、计分或增加找到的猫。

### HTTP 集成测试

- 首页、静态资源和 `/health` 返回 `200`。
- 尺寸 `3`、`10`、非整数和多个数值返回 `400`。
- 没有游戏时读取或猜测返回明确错误。
- 越界坐标返回 `400`。
- 合法猜测更新分数、次数、格子状态和完成状态。
- JSON 不包含 `solution` 或任何隐藏猫信息。

### 手动验收

- 完成一局 `4×4`、`5×5` 和 `9×9`。
- 验证刷新后当前游戏仍存在。
- 验证新游戏清除旧棋盘和浏览器标记。
- 验证猜猫和标记排除两种模式。
- 验证手机宽度与键盘操作。

## Build and Run

```powershell
javac --add-modules jdk.httpserver -d out *.java
java --add-modules jdk.httpserver -cp out WebMain
```

浏览器访问：

```text
http://localhost:8080
```

## Assumptions

- 继续使用 Java 17 和默认 package。
- 不引入 Maven、Gradle、Spring、前端框架或 JSON library。
- 第一阶段不加入账号、数据库、排行榜、多人游戏或永久存档。
- 公网发布和自定义域名在本地 UI 稳定后单独规划。

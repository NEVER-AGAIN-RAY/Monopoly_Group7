package com.monopoly.model.rules;

/**
 * 局域网多人对战操作指南 — 提供 HTML 富文本版本供 WebView 渲染，
 * 以及纯文本版本供降级场景使用。
 */
public final class MultiplayerGuideSummary {

    private MultiplayerGuideSummary() {
    }

    private static final String CSS = String.join("\n",
        "<style>",
        "* { margin: 0; padding: 0; box-sizing: border-box; }",
        "body {",
        "  font-family: -apple-system, 'Segoe UI', 'Microsoft YaHei', 'PingFang SC', sans-serif;",
        "  background: linear-gradient(135deg, #e8eaf6 0%, #e3f2fd 50%, #e0f7fa 100%);",
        "  color: #263238;",
        "  line-height: 1.7;",
        "  padding: 32px 24px 48px;",
        "  min-height: 100vh;",
        "}",
        ".hero {",
        "  text-align: center;",
        "  padding: 40px 24px 32px;",
        "  margin-bottom: 28px;",
        "  background: linear-gradient(135deg, #1565c0 0%, #0d47a1 60%, #1a237e 100%);",
        "  border-radius: 16px;",
        "  color: white;",
        "  box-shadow: 0 8px 32px rgba(13,71,161,0.25);",
        "}",
        ".hero h1 {",
        "  font-size: 28px;",
        "  font-weight: 700;",
        "  margin-bottom: 8px;",
        "  letter-spacing: 1px;",
        "}",
        ".hero p {",
        "  font-size: 14px;",
        "  opacity: 0.88;",
        "}",
        ".section {",
        "  background: white;",
        "  border-radius: 12px;",
        "  padding: 24px 28px;",
        "  margin-bottom: 20px;",
        "  box-shadow: 0 2px 12px rgba(0,0,0,0.06);",
        "  border-left: 4px solid #1976d2;",
        "}",
        ".section.warn {",
        "  border-left-color: #f57c00;",
        "}",
        ".section.tip {",
        "  border-left-color: #43a047;",
        "}",
        ".section.faq {",
        "  border-left-color: #7b1fa2;",
        "}",
        ".section-header {",
        "  display: flex;",
        "  align-items: center;",
        "  margin-bottom: 16px;",
        "}",
        ".step-badge {",
        "  display: inline-flex;",
        "  align-items: center;",
        "  justify-content: center;",
        "  width: 36px;",
        "  height: 36px;",
        "  border-radius: 50%;",
        "  background: linear-gradient(135deg, #1e88e5, #1565c0);",
        "  color: white;",
        "  font-size: 16px;",
        "  font-weight: 700;",
        "  margin-right: 14px;",
        "  flex-shrink: 0;",
        "  box-shadow: 0 2px 8px rgba(21,101,192,0.3);",
        "}",
        ".step-badge.orange {",
        "  background: linear-gradient(135deg, #fb8c00, #f57c00);",
        "  box-shadow: 0 2px 8px rgba(245,124,0,0.3);",
        "}",
        ".step-badge.green {",
        "  background: linear-gradient(135deg, #43a047, #2e7d32);",
        "  box-shadow: 0 2px 8px rgba(67,160,71,0.3);",
        "}",
        ".step-badge.purple {",
        "  background: linear-gradient(135deg, #8e24aa, #6a1b9a);",
        "  box-shadow: 0 2px 8px rgba(142,36,170,0.3);",
        "}",
        ".icon-badge {",
        "  display: inline-flex;",
        "  align-items: center;",
        "  justify-content: center;",
        "  width: 36px;",
        "  height: 36px;",
        "  border-radius: 50%;",
        "  font-size: 18px;",
        "  margin-right: 14px;",
        "  flex-shrink: 0;",
        "}",
        ".icon-badge.blue  { background: #e3f2fd; }",
        ".icon-badge.amber { background: #fff8e1; }",
        ".icon-badge.green { background: #e8f5e9; }",
        ".icon-badge.purple{ background: #f3e5f5; }",
        "h2 {",
        "  font-size: 18px;",
        "  font-weight: 700;",
        "  color: #1565c0;",
        "}",
        ".section.warn h2 { color: #e65100; }",
        ".section.tip h2  { color: #2e7d32; }",
        ".section.faq h2  { color: #6a1b9a; }",
        "p, li {",
        "  font-size: 14px;",
        "  color: #37474f;",
        "  margin-bottom: 8px;",
        "}",
        "ul { padding-left: 20px; margin-bottom: 8px; }",
        "li { margin-bottom: 6px; }",
        ".cmd {",
        "  display: block;",
        "  background: #263238;",
        "  color: #80cbc4;",
        "  font-family: 'Menlo', 'Consolas', 'Courier New', monospace;",
        "  font-size: 13px;",
        "  padding: 12px 16px;",
        "  border-radius: 8px;",
        "  margin: 10px 0 12px;",
        "  overflow-x: auto;",
        "  letter-spacing: 0.3px;",
        "}",
        ".cmd .prompt { color: #4dd0e1; }",
        ".highlight {",
        "  background: #fff3e0;",
        "  border: 1px solid #ffe0b2;",
        "  border-radius: 8px;",
        "  padding: 12px 16px;",
        "  margin: 10px 0;",
        "  font-size: 13px;",
        "  color: #e65100;",
        "}",
        ".highlight.blue {",
        "  background: #e3f2fd;",
        "  border-color: #bbdefb;",
        "  color: #0d47a1;",
        "}",
        ".tag {",
        "  display: inline-block;",
        "  font-size: 11px;",
        "  font-weight: 600;",
        "  padding: 2px 10px;",
        "  border-radius: 12px;",
        "  margin-right: 6px;",
        "  vertical-align: middle;",
        "}",
        ".tag.win  { background: #e3f2fd; color: #1565c0; }",
        ".tag.mac  { background: #e8eaf6; color: #283593; }",
        ".tag.lnx  { background: #e8f5e9; color: #2e7d32; }",
        ".faq-item {",
        "  background: #fafafa;",
        "  border-radius: 8px;",
        "  padding: 14px 18px;",
        "  margin-bottom: 12px;",
        "}",
        ".faq-q {",
        "  font-weight: 700;",
        "  color: #37474f;",
        "  margin-bottom: 6px;",
        "  font-size: 14px;",
        "}",
        ".faq-a {",
        "  color: #546e7a;",
        "  font-size: 13px;",
        "}",
        ".prereq-grid {",
        "  display: flex;",
        "  gap: 12px;",
        "  flex-wrap: wrap;",
        "  margin-top: 12px;",
        "}",
        ".prereq-card {",
        "  flex: 1;",
        "  min-width: 140px;",
        "  background: #f5f5f5;",
        "  border-radius: 10px;",
        "  padding: 16px;",
        "  text-align: center;",
        "}",
        ".prereq-card .emoji { font-size: 28px; margin-bottom: 6px; }",
        ".prereq-card .label { font-weight: 600; font-size: 14px; color: #37474f; }",
        ".prereq-card .desc  { font-size: 12px; color: #78909c; margin-top: 4px; }",
        "strong { color: #1565c0; }",
        "code {",
        "  background: #eceff1;",
        "  padding: 2px 6px;",
        "  border-radius: 4px;",
        "  font-size: 13px;",
        "  font-family: 'Menlo', 'Consolas', monospace;",
        "  color: #d32f2f;",
        "}",
        ".footer {",
        "  text-align: center;",
        "  color: #90a4ae;",
        "  font-size: 12px;",
        "  margin-top: 24px;",
        "  padding-top: 16px;",
        "  border-top: 1px solid #e0e0e0;",
        "}",
        "</style>"
    );

    // ─────────────────── Chinese HTML ───────────────────

    public static String buildHtmlChinese() {
        return "<!DOCTYPE html><html lang='zh'><head><meta charset='UTF-8'>"
            + CSS + "</head><body>"
            + heroZh()
            + prereqZh()
            + step1Zh()
            + step2Zh()
            + step3Zh()
            + step4Zh()
            + step5Zh()
            + step6Zh()
            + hostPlayZh()
            + soloZh()
            + faqZh()
            + "<div class='footer'>" + esc("默认端口 8025 · 路径 /ws · 若修改了端口请全文替换") + "</div>"
            + "</body></html>";
    }

    private static String heroZh() {
        return "<div class='hero'>"
            + "<h1>&#x1F3AE; " + esc("局域网多人对战指南") + "</h1>"
            + "<p>" + esc("不需要云服务器 \u2014 用一台电脑当主机，其他人连进来一起玩") + "</p>"
            + "</div>";
    }

    private static String prereqZh() {
        return "<div class='section'>"
            + "<div class='section-header'><div class='icon-badge blue'>&#x1F4CB;</div>"
            + "<h2>" + esc("准备工作") + "</h2></div>"
            + "<div class='prereq-grid'>"
            + "<div class='prereq-card'><div class='emoji'>&#x1F4F6;</div>"
            + "<div class='label'>" + esc("同一网络") + "</div>"
            + "<div class='desc'>" + esc("所有电脑连同一 Wi-Fi") + "<br/>" + esc("手机热点也可以") + "</div></div>"
            + "<div class='prereq-card'><div class='emoji'>&#x2615;</div>"
            + "<div class='label'>JDK 17 + Maven</div>"
            + "<div class='desc'>" + esc("每台电脑能运行") + "<br/>" + esc("本项目的客户端") + "</div></div>"
            + "<div class='prereq-card'><div class='emoji'>&#x1F465;</div>"
            + "<div class='label'>" + esc("2～5 名玩家") + "</div>"
            + "<div class='desc'>" + esc("开局时人数") + "<br/>" + esc("要与实际一致") + "</div></div>"
            + "</div></div>";
    }

    private static String step1Zh() {
        return "<div class='section'>"
            + "<div class='section-header'><div class='step-badge'>1</div>"
            + "<h2>" + esc("选一台电脑当「主机」启动服务器") + "</h2></div>"
            + "<p>" + esc("这台电脑会一直处于「开服」状态，建议选电量充足或插电的笔记本，关闭休眠以免断线。") + "</p>"
            + "<p>" + esc("打开终端，进入项目文件夹（有 ") + "<code>pom.xml</code>" + esc(" 的目录），执行：") + "</p>"
            + "<div class='cmd'><span class='prompt'>$</span> mvn -q exec:java</div>"
            + "<p>" + esc("看到类似以下提示表示服务已启动：") + "</p>"
            + "<div class='highlight blue'>Monopoly WebSocket running at ws://localhost:8025/ws</div>"
            + "<div class='highlight'>&#x26A0; " + esc("这个终端窗口不要关闭，关掉 = 关服！") + "</div>"
            + "</div>";
    }

    private static String step2Zh() {
        return "<div class='section'>"
            + "<div class='section-header'><div class='step-badge'>2</div>"
            + "<h2>" + esc("记下主机的「局域网 IP」") + "</h2></div>"
            + "<p>" + esc("其他玩家连接时需要填主机的内网 IP（不是 ") + "<code>localhost</code>" + esc("）。") + "</p>"
            + "<ul>"
            + "<li><span class='tag win'>Windows</span> " + esc("设置 → 网络 → WLAN → 当前网络 → IPv4 地址；或命令行 ") + "<code>ipconfig</code></li>"
            + "<li><span class='tag mac'>macOS</span> " + esc("系统设置 → 网络 → Wi-Fi → IP 地址；或命令行 ") + "<code>ifconfig</code></li>"
            + "<li><span class='tag lnx'>Linux</span> " + esc("命令行 ") + "<code>ip addr</code>" + esc("，找 ") + "<code>192.168.x.x</code>" + esc(" 或 ") + "<code>10.x.x.x</code></li>"
            + "</ul>"
            + "<p>" + esc("例如主机 IP 是 ") + "<strong>192.168.1.100</strong>" + esc("（请替换为你们自己的）。") + "</p>"
            + "</div>";
    }

    private static String step3Zh() {
        return "<div class='section warn'>"
            + "<div class='section-header'><div class='step-badge orange'>3</div>"
            + "<h2>" + esc("主机放行防火墙（很重要）") + "</h2></div>"
            + "<p>" + esc("不放行的话，同 Wi-Fi 的其他电脑也会") + "<strong style='color:#e65100'>" + esc("连不上") + "</strong>" + esc("。") + "</p>"
            + "<ul>"
            + "<li><span class='tag win'>Windows</span> " + esc("控制面板 → Windows 安全中心 → 防火墙 → 高级设置 → 入站规则 → 新建规则 → 端口 → TCP → 填 ") + "<code>8025</code>" + esc(" → 允许连接") + "</li>"
            + "<li><span class='tag mac'>macOS</span> " + esc("系统设置 → 网络 → 防火墙 → 选项 → 若 Java 弹窗询问是否允许传入连接 → 选择「允许」") + "</li>"
            + "</ul>"
            + "</div>";
    }

    private static String step4Zh() {
        return "<div class='section'>"
            + "<div class='section-header'><div class='step-badge'>4</div>"
            + "<h2>" + esc("其他玩家启动客户端并连接") + "</h2></div>"
            + "<p>" + esc("每台参与游戏的电脑都需安装同一套项目。") + "</p>"
            + "<p><strong>" + esc("启动客户端：") + "</strong></p>"
            + "<div class='cmd'><span class='prompt'>$</span> mvn javafx:run</div>"
            + "<p><strong>" + esc("修改服务器地址：") + "</strong>" + esc("在界面展开「高级设置」，将 WebSocket 地址改为：") + "</p>"
            + "<div class='cmd'>ws://192.168.1.100:8025/ws</div>"
            + "<div class='highlight'>&#x26A0; " + esc("不要用 ") + "<code>ws://localhost:8025/ws</code>" + esc("，那只连自己的电脑！") + "</div>"
            + "<p>" + esc("点击「连接」按钮，若显示") + "<strong>" + esc("已连接") + "</strong>" + esc("，说明网络通了。") + "</p>"
            + "</div>";
    }

    private static String step5Zh() {
        return "<div class='section'>"
            + "<div class='section-header'><div class='step-badge'>5</div>"
            + "<h2>" + esc("每位玩家设置不同的玩家 ID") + "</h2></div>"
            + "<p>" + esc("游戏模式选择 ") + "<strong>PVP</strong>" + esc("（人人对战）后，在「高级设置」中：") + "</p>"
            + "<ul>"
            + "<li>" + esc("第 1 位玩家 → 玩家 ID 填 ") + "<code>pvp-1</code>" + esc("，点「仅认证」") + "</li>"
            + "<li>" + esc("第 2 位玩家 → 玩家 ID 填 ") + "<code>pvp-2</code>" + esc("，点「仅认证」") + "</li>"
            + "<li>" + esc("第 3 位玩家 → ") + "<code>pvp-3</code> " + esc("…… 依此类推") + "</li>"
            + "</ul>"
            + "<div class='highlight'>&#x26A0; " + esc("不要两个人用同一个编号，否则游戏会乱套！") + "</div>"
            + "</div>";
    }

    private static String step6Zh() {
        return "<div class='section tip'>"
            + "<div class='section-header'><div class='step-badge green'>6</div>"
            + "<h2>" + esc("开局") + "</h2></div>"
            + "<p><strong>" + esc("推荐流程：") + "</strong></p>"
            + "<ul>"
            + "<li>" + esc("主机先开服（第一步），保持运行") + "</li>"
            + "<li><strong>" + esc("所有玩家") + "</strong>" + esc("都先连接成功，并完成各自 ") + "<code>pvp-x</code>" + esc(" 的认证") + "</li>"
            + "<li>" + esc("约定好由") + "<strong>" + esc("其中一位") + "</strong>" + esc("（一般是主机玩家）点击「仅开局」：") + "<br/>"
            + esc("　　模式选 ") + "<strong>PVP</strong>" + esc("，玩家数量填实际人数") + "</li>"
            + "<li>" + esc("只点") + "<strong>" + esc("一次") + "</strong>" + esc("开局即可，所有客户端都会自动收到游戏状态") + "</li>"
            + "</ul>"
            + "<div class='highlight'>&#x26A0; " + esc("若有人还没连上或未认证就开局，可能出现异常；遇到问题时先全员重连，由房主重新开一局。") + "</div>"
            + "</div>";
    }

    private static String hostPlayZh() {
        return "<div class='section'>"
            + "<div class='section-header'><div class='icon-badge green'>&#x1F4BB;</div>"
            + "<h2>" + esc("主机自己也想一起玩？") + "</h2></div>"
            + "<p>" + esc("完全可以！同一台电脑上：") + "</p>"
            + "<ul>"
            + "<li>" + esc("一个终端窗口继续跑服务器 ") + "<code>mvn -q exec:java</code></li>"
            + "<li><strong>" + esc("再开一个终端") + "</strong>" + esc("，执行 ") + "<code>mvn javafx:run</code>" + esc(" 打开客户端") + "</li>"
            + "<li>" + esc("地址填 ") + "<code>ws://localhost:8025/ws</code>" + esc("，玩家 ID 填 ") + "<code>pvp-1</code>" + esc("（或约定编号），再和大家一起认证、开局") + "</li>"
            + "</ul>"
            + "</div>";
    }

    private static String soloZh() {
        return "<div class='section tip'>"
            + "<div class='section-header'><div class='icon-badge green'>&#x1F916;</div>"
            + "<h2>" + esc("单人模式（人机对战）快速开局") + "</h2></div>"
            + "<p>" + esc("如果只是一个人想玩，不需要找队友：") + "</p>"
            + "<ul>"
            + "<li>" + esc("启动服务器：") + "<code>mvn -q exec:java</code></li>"
            + "<li>" + esc("启动客户端：") + "<code>mvn javafx:run</code></li>"
            + "<li>" + esc("游戏模式选 ") + "<strong>HVM</strong>" + esc("（Human vs Machine），直接点「开始游戏」即可") + "</li>"
            + "<li>" + esc("无需手动填地址或玩家 ID，一键搞定") + "</li>"
            + "</ul>"
            + "</div>";
    }

    private static String faqZh() {
        return "<div class='section faq'>"
            + "<div class='section-header'><div class='step-badge purple'>?</div>"
            + "<h2>" + esc("常见问题排查") + "</h2></div>"
            + "<div class='faq-item'><div class='faq-q'>&#x1F534; " + esc("别人填了 ws://主机IP:8025/ws 仍连不上") + "</div>"
            + "<div class='faq-a'>" + esc("检查防火墙是否已放行 8025 端口；核对 IP 是否正确；确认所有人在同一 Wi-Fi。") + "</div></div>"
            + "<div class='faq-item'><div class='faq-q'>&#x1F534; " + esc("连上了但操作不像自己") + "</div>"
            + "<div class='faq-a'>" + esc("玩家 ID 和别人重复了。每人用不同的 ") + "<code>pvp-x</code>" + esc(" 编号并重新认证。") + "</div></div>"
            + "<div class='faq-item'><div class='faq-q'>&#x1F534; " + esc("主机休眠 / 断网") + "</div>"
            + "<div class='faq-a'>" + esc("插电、关闭休眠、主机保持亮屏或调整电源选项。") + "</div></div>"
            + "<div class='faq-item'><div class='faq-q'>&#x1F534; " + esc("想换一局") + "</div>"
            + "<div class='faq-a'>" + esc("全员退出客户端后重连，按「第四步」重新操作即可。") + "</div></div>"
            + "</div>";
    }

    // ─────────────────── English HTML ───────────────────

    public static String buildHtmlEnglish() {
        return "<!DOCTYPE html><html lang='en'><head><meta charset='UTF-8'>"
            + CSS + "</head><body>"
            + heroEn()
            + prereqEn()
            + step1En()
            + step2En()
            + step3En()
            + step4En()
            + step5En()
            + step6En()
            + hostPlayEn()
            + soloEn()
            + faqEn()
            + "<div class='footer'>Default port 8025 &middot; path /ws &middot; replace 8025 if you changed the port in code</div>"
            + "</body></html>";
    }

    private static String heroEn() {
        return "<div class='hero'>"
            + "<h1>&#x1F3AE; LAN Multiplayer Guide</h1>"
            + "<p>No cloud server needed &mdash; one computer hosts, others join over the local network</p>"
            + "</div>";
    }

    private static String prereqEn() {
        return "<div class='section'>"
            + "<div class='section-header'><div class='icon-badge blue'>&#x1F4CB;</div><h2>Prerequisites</h2></div>"
            + "<div class='prereq-grid'>"
            + "<div class='prereq-card'><div class='emoji'>&#x1F4F6;</div><div class='label'>Same Network</div><div class='desc'>All computers on the<br/>same Wi-Fi or LAN</div></div>"
            + "<div class='prereq-card'><div class='emoji'>&#x2615;</div><div class='label'>JDK 17 + Maven</div><div class='desc'>Each PC can run<br/>this project's client</div></div>"
            + "<div class='prereq-card'><div class='emoji'>&#x1F465;</div><div class='label'>2-5 Players</div><div class='desc'>Player count at start<br/>must match actual</div></div>"
            + "</div></div>";
    }

    private static String step1En() {
        return "<div class='section'>"
            + "<div class='section-header'><div class='step-badge'>1</div><h2>Choose a Host &amp; Start the Server</h2></div>"
            + "<p>This computer will keep running the server. Keep it plugged in and awake.</p>"
            + "<p>Open a terminal, navigate to the project folder (with <code>pom.xml</code>), and run:</p>"
            + "<div class='cmd'><span class='prompt'>$</span> mvn -q exec:java</div>"
            + "<p>When you see this, the server is ready:</p>"
            + "<div class='highlight blue'>Monopoly WebSocket running at ws://localhost:8025/ws</div>"
            + "<div class='highlight'>&#x26A0; Do NOT close this terminal window &mdash; closing it shuts down the server!</div>"
            + "</div>";
    }

    private static String step2En() {
        return "<div class='section'>"
            + "<div class='section-header'><div class='step-badge'>2</div><h2>Find the Host's LAN IP</h2></div>"
            + "<p>Other players need this IP to connect (not <code>localhost</code>).</p>"
            + "<ul>"
            + "<li><span class='tag win'>Windows</span> Settings &rarr; Network &rarr; Wi-Fi &rarr; IPv4 address; or run <code>ipconfig</code></li>"
            + "<li><span class='tag mac'>macOS</span> System Settings &rarr; Network &rarr; Wi-Fi &rarr; IP Address; or run <code>ifconfig</code></li>"
            + "<li><span class='tag lnx'>Linux</span> Run <code>ip addr</code>, look for <code>192.168.x.x</code> or <code>10.x.x.x</code></li>"
            + "</ul>"
            + "<p>Example: host IP is <strong>192.168.1.100</strong> (replace with yours).</p>"
            + "</div>";
    }

    private static String step3En() {
        return "<div class='section warn'>"
            + "<div class='section-header'><div class='step-badge orange'>3</div><h2>Allow Firewall Access (Important!)</h2></div>"
            + "<p>Without this, other computers <strong style='color:#e65100'>cannot connect</strong> even on the same Wi-Fi.</p>"
            + "<ul>"
            + "<li><span class='tag win'>Windows</span> Control Panel &rarr; Windows Security &rarr; Firewall &rarr; Advanced &rarr; Inbound Rules &rarr; New Rule &rarr; Port &rarr; TCP &rarr; <code>8025</code> &rarr; Allow</li>"
            + "<li><span class='tag mac'>macOS</span> System Settings &rarr; Network &rarr; Firewall &rarr; Options &rarr; Allow incoming connections for Java</li>"
            + "</ul>"
            + "</div>";
    }

    private static String step4En() {
        return "<div class='section'>"
            + "<div class='section-header'><div class='step-badge'>4</div><h2>Other Players: Launch &amp; Connect</h2></div>"
            + "<p>Each player's computer needs the same project installed.</p>"
            + "<p><strong>Start the client:</strong></p>"
            + "<div class='cmd'><span class='prompt'>$</span> mvn javafx:run</div>"
            + "<p><strong>Change server address:</strong> Expand \"Advanced Settings\", change the WebSocket URL to:</p>"
            + "<div class='cmd'>ws://192.168.1.100:8025/ws</div>"
            + "<div class='highlight'>&#x26A0; Do NOT use <code>ws://localhost:8025/ws</code> &mdash; that only connects to yourself!</div>"
            + "<p>Click <strong>Connect</strong>. If it shows \"Connected\", you're good.</p>"
            + "</div>";
    }

    private static String step5En() {
        return "<div class='section'>"
            + "<div class='section-header'><div class='step-badge'>5</div><h2>Each Player Sets a Unique ID</h2></div>"
            + "<p>Select <strong>PVP</strong> mode, then in \"Advanced Settings\":</p>"
            + "<ul>"
            + "<li>Player 1 &rarr; Player ID: <code>pvp-1</code>, click \"Auth Only\"</li>"
            + "<li>Player 2 &rarr; Player ID: <code>pvp-2</code>, click \"Auth Only\"</li>"
            + "<li>Player 3 &rarr; <code>pvp-3</code> &hellip; and so on</li>"
            + "</ul>"
            + "<div class='highlight'>&#x26A0; Do NOT use duplicate IDs &mdash; it will cause confusion!</div>"
            + "</div>";
    }

    private static String step6En() {
        return "<div class='section tip'>"
            + "<div class='section-header'><div class='step-badge green'>6</div><h2>Start the Game</h2></div>"
            + "<p><strong>Recommended flow:</strong></p>"
            + "<ul>"
            + "<li>Host starts the server (Step 1), keeps it running</li>"
            + "<li><strong>All players</strong> connect and authenticate with their <code>pvp-x</code> ID</li>"
            + "<li>One player (usually the host) clicks \"Start Only\":<br/>"
            + "Mode: <strong>PVP</strong>, player count = actual number of players</li>"
            + "<li>Click start <strong>once</strong> &mdash; all clients receive the game state automatically</li>"
            + "</ul>"
            + "<div class='highlight'>&#x26A0; If someone hasn't connected/authenticated before start, they may not join properly. Fix: everyone reconnects, host restarts the game.</div>"
            + "</div>";
    }

    private static String hostPlayEn() {
        return "<div class='section'>"
            + "<div class='section-header'><div class='icon-badge green'>&#x1F4BB;</div><h2>Host Wants to Play Too?</h2></div>"
            + "<p>Absolutely! On the same computer:</p>"
            + "<ul>"
            + "<li>One terminal keeps running the server: <code>mvn -q exec:java</code></li>"
            + "<li>Open <strong>another terminal</strong>: <code>mvn javafx:run</code> for the client</li>"
            + "<li>Use <code>ws://localhost:8025/ws</code>, Player ID <code>pvp-1</code> (or your agreed ID), then auth &amp; start with everyone</li>"
            + "</ul>"
            + "</div>";
    }

    private static String soloEn() {
        return "<div class='section tip'>"
            + "<div class='section-header'><div class='icon-badge green'>&#x1F916;</div><h2>Single Player (vs AI) Quick Start</h2></div>"
            + "<p>Just want to play solo?</p>"
            + "<ul>"
            + "<li>Start server: <code>mvn -q exec:java</code></li>"
            + "<li>Start client: <code>mvn javafx:run</code></li>"
            + "<li>Set mode to <strong>HVM</strong> (Human vs Machine), click \"Start Game\"</li>"
            + "<li>No manual address or Player ID needed &mdash; one click to go!</li>"
            + "</ul>"
            + "</div>";
    }

    private static String faqEn() {
        return "<div class='section faq'>"
            + "<div class='section-header'><div class='step-badge purple'>?</div><h2>Troubleshooting</h2></div>"
            + "<div class='faq-item'><div class='faq-q'>&#x1F534; Can't connect to ws://host-IP:8025/ws</div>"
            + "<div class='faq-a'>Check firewall, verify IP, ensure all on same Wi-Fi.</div></div>"
            + "<div class='faq-item'><div class='faq-q'>&#x1F534; Connected but actions don't match mine</div>"
            + "<div class='faq-a'>Duplicate Player ID. Each player must use a unique <code>pvp-x</code> and re-authenticate.</div></div>"
            + "<div class='faq-item'><div class='faq-q'>&#x1F534; Host goes to sleep / disconnects</div>"
            + "<div class='faq-a'>Keep plugged in, disable sleep, keep screen on.</div></div>"
            + "<div class='faq-item'><div class='faq-q'>&#x1F534; Want a new game</div>"
            + "<div class='faq-a'>Everyone exits clients, reconnects, and follows Step 4 again.</div></div>"
            + "</div>";
    }

    /**
     * Escapes special HTML characters (&amp; &lt; &gt;) so that literal
     * Chinese text can be safely embedded in HTML without entity issues.
     */
    private static String esc(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ─────────────────── Plain Text (fallback) ───────────────────

    public static String buildPlainTextChinese() {
        return "局域网多人对战操作指南\n\n"
            + "准备：所有电脑连同一 Wi-Fi；已安装 JDK 17 + Maven；2～5 人。\n\n"
            + "1. 选一台电脑当主机，执行 mvn -q exec:java 启动服务器（窗口不要关）。\n"
            + "2. 记下主机局域网 IP（如 192.168.1.100）。\n"
            + "3. 主机放行防火墙 TCP 8025 端口。\n"
            + "4. 其他玩家执行 mvn javafx:run，将地址改为 ws://主机IP:8025/ws，点连接。\n"
            + "5. 每位玩家设不同 ID（pvp-1、pvp-2 …），点仅认证。\n"
            + "6. 其中一位点仅开局（PVP，人数与实际一致），所有人收到游戏状态。\n\n"
            + "主机也想玩：再开一个终端跑 mvn javafx:run，地址 ws://localhost:8025/ws。\n"
            + "单人模式：模式选 HVM，直接点开始游戏。\n";
    }

    public static String buildPlainTextEnglish() {
        return "LAN Multiplayer Guide\n\n"
            + "Prerequisites: Same Wi-Fi; JDK 17 + Maven; 2-5 players.\n\n"
            + "1. Choose a host, run mvn -q exec:java (keep terminal open).\n"
            + "2. Note host LAN IP (e.g. 192.168.1.100).\n"
            + "3. Allow firewall TCP port 8025 on host.\n"
            + "4. Others run mvn javafx:run, change URL to ws://host-IP:8025/ws, click Connect.\n"
            + "5. Each player sets unique ID (pvp-1, pvp-2...), click Auth Only.\n"
            + "6. One player clicks Start Only (PVP, correct player count).\n\n"
            + "Host plays too: open another terminal for mvn javafx:run, use ws://localhost:8025/ws.\n"
            + "Solo: set mode HVM, click Start Game.\n";
    }
}

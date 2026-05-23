package com.monopoly.model.rules;

import com.monopoly.model.core.GameConstants;
import com.monopoly.model.settlement.PropertySetCalculator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Embedded Chinese rules text for the client panel; factory/deck is authoritative.
 */
public final class MonopolyDealRulesSummary {

    private MonopolyDealRulesSummary() {
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
        "  padding: 36px 24px 28px;",
        "  margin-bottom: 28px;",
        "  background: linear-gradient(135deg, #1565c0 0%, #0d47a1 60%, #1a237e 100%);",
        "  border-radius: 16px;",
        "  color: white;",
        "  box-shadow: 0 8px 32px rgba(13,71,161,0.25);",
        "}",
        ".hero h1 { font-size: 28px; font-weight: 700; margin-bottom: 8px; letter-spacing: 1px; }",
        ".hero p { font-size: 14px; opacity: 0.88; }",
        ".card {",
        "  background: white;",
        "  border-radius: 12px;",
        "  padding: 22px 26px;",
        "  margin-bottom: 18px;",
        "  box-shadow: 0 2px 12px rgba(0,0,0,0.06);",
        "  border-left: 4px solid #1976d2;",
        "}",
        ".card.victory  { border-left-color: #f9a825; }",
        ".card.turn     { border-left-color: #43a047; }",
        ".card.payment  { border-left-color: #e53935; }",
        ".card.bank     { border-left-color: #7b1fa2; }",
        ".card.property { border-left-color: #00897b; }",
        ".card.wild     { border-left-color: #f06292; }",
        ".card.play     { border-left-color: #1e88e5; }",
        ".card.rent     { border-left-color: #ff8f00; }",
        ".card.sets     { border-left-color: #5e35b1; }",
        ".card.effects  { border-left-color: #d81b60; }",
        ".card-header { display: flex; align-items: center; margin-bottom: 12px; }",
        ".icon {",
        "  display: inline-flex; align-items: center; justify-content: center;",
        "  width: 36px; height: 36px; border-radius: 10px;",
        "  font-size: 18px; margin-right: 14px; flex-shrink: 0;",
        "}",
        ".icon.blue   { background: #e3f2fd; }",
        ".icon.yellow { background: #fff8e1; }",
        ".icon.green  { background: #e8f5e9; }",
        ".icon.red    { background: #ffebee; }",
        ".icon.purple { background: #f3e5f5; }",
        ".icon.teal   { background: #e0f2f1; }",
        ".icon.pink   { background: #fce4ec; }",
        ".icon.amber  { background: #fff8e1; }",
        ".icon.deep   { background: #ede7f6; }",
        ".icon.rose   { background: #fce4ec; }",
        "h2 { font-size: 17px; font-weight: 700; color: #1565c0; }",
        ".card.victory h2  { color: #f57f17; }",
        ".card.turn h2     { color: #2e7d32; }",
        ".card.payment h2  { color: #c62828; }",
        ".card.bank h2     { color: #6a1b9a; }",
        ".card.property h2 { color: #00695c; }",
        ".card.wild h2     { color: #c2185b; }",
        ".card.play h2     { color: #1565c0; }",
        ".card.rent h2     { color: #e65100; }",
        ".card.sets h2     { color: #4527a0; }",
        ".card.effects h2  { color: #ad1457; }",
        "p, li { font-size: 14px; color: #37474f; margin-bottom: 6px; }",
        "ul { padding-left: 20px; margin-bottom: 6px; }",
        "li { margin-bottom: 4px; }",
        "table { width: 100%; border-collapse: collapse; margin: 10px 0; font-size: 13px; }",
        "th { background: #e3f2fd; color: #1565c0; font-weight: 600; padding: 8px 12px; text-align: left; border-bottom: 2px solid #bbdefb; }",
        "td { padding: 6px 12px; border-bottom: 1px solid #eceff1; color: #455a64; }",
        "tr:hover td { background: #f5f5f5; }",
        "code { background: #eceff1; padding: 2px 6px; border-radius: 4px; font-size: 13px; font-family: 'Menlo','Consolas',monospace; color: #d32f2f; }",
        "strong { color: #1565c0; }",
        ".highlight { background: #e3f2fd; border: 1px solid #bbdefb; border-radius: 8px; padding: 10px 14px; margin: 8px 0; font-size: 13px; color: #0d47a1; }",
        ".color-chip { display: inline-block; padding: 3px 10px; border-radius: 12px; font-size: 12px; font-weight: 600; margin: 2px 3px; color: white; }",
        ".footer { text-align: center; color: #90a4ae; font-size: 12px; margin-top: 24px; padding-top: 16px; border-top: 1px solid #e0e0e0; }",
        "</style>"
    );

    private static String esc(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ─────────────────── Chinese HTML ───────────────────

    public static String buildHtmlChinese() {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang='zh'><head><meta charset='UTF-8'>").append(CSS).append("</head><body>");

        sb.append("<div class='hero'><h1>&#x1F0CF; Monopoly Deal ").append(esc("游戏规则")).append("</h1>");
        sb.append("<p>").append(esc("全部规则一览 \u2014 快速上手，轻松对战")).append("</p></div>");

        // Deck
        sb.append("<div class='card'>");
        sb.append("<div class='card-header'><div class='icon blue'>&#x1F0CF;</div><h2>").append(esc("牌堆组成")).append("</h2></div>");
        sb.append("<p>").append(esc("本客户端使用 ")).append("<strong>").append(GameConstants.STANDARD_DECK_SIZE).append(" ").append(esc("张可游戏牌")).append("</strong>").append(esc("（108 张清单中 2 张规则卡不入牌堆）：")).append("</p>");
        sb.append("<table><tr><th>").append(esc("类型")).append("</th><th>").append(esc("数量")).append("</th><th>").append(esc("说明")).append("</th></tr>");
        sb.append("<tr><td>").append(esc("房产")).append("</td><td>28</td><td>").append(esc("各色基本房产牌")).append("</td></tr>");
        sb.append("<tr><td>").append(esc("万能")).append("</td><td>11</td><td>2 ").append(esc("张任意色 + 9 张印定双色")).append("</td></tr>");
        sb.append("<tr><td>").append(esc("钱币")).append("</td><td>20</td><td>").append(esc("各面额现金牌")).append("</td></tr>");
        sb.append("<tr><td>").append(esc("行动")).append("</td><td>34</td><td>").append(esc("PASS_GO 10、交易/讨债/房屋/旅馆等 24")).append("</td></tr>");
        sb.append("<tr><td>").append(esc("租金")).append("</td><td>13</td><td>").append(esc("任意色 3、双色 1v1 共 10")).append("</td></tr>");
        sb.append("</table></div>");

        // Victory
        sb.append("<div class='card victory'>");
        sb.append("<div class='card-header'><div class='icon yellow'>&#x1F3C6;</div><h2>").append(esc("胜利条件")).append("</h2></div>");
        sb.append("<p>").append(esc("先凑齐 ")).append("<strong>3 ").append(esc("套不同颜色")).append("</strong>").append(esc("的完整房产集的玩家获胜。")).append("</p></div>");

        // Turn
        sb.append("<div class='card turn'>");
        sb.append("<div class='card-header'><div class='icon green'>&#x1F504;</div><h2>").append(esc("回合流程")).append("</h2></div>");
        sb.append("<ul>");
        sb.append("<li><strong>").append(esc("摸牌")).append("</strong>").append(esc("：摸 2 张（手牌为空则摸 5 张）")).append("</li>");
        sb.append("<li><strong>").append(esc("出牌")).append("</strong>").append(esc("：最多打出 3 张")).append("</li>");
        sb.append("<li><strong>").append(esc("弃牌")).append("</strong>").append(esc("：手牌超过 7 张须弃至 7 张")).append("</li>");
        sb.append("</ul></div>");

        // Payment
        sb.append("<div class='card payment'>");
        sb.append("<div class='card-header'><div class='icon red'>&#x1F4B0;</div><h2>").append(esc("支付规则")).append("</h2></div>");
        sb.append("<ul>");
        sb.append("<li>").append(esc("仅能从")).append("<strong>").append(esc("银行区与财产区")).append("</strong>").append(esc("支付，不能从手牌直接支付")).append("</li>");
        sb.append("<li>").append(esc("多付不退")).append("</li>");
        sb.append("<li>").append(esc("可在客户端勾选具体支付牌；未指定时服务器按小面额优先自动凑额")).append("</li>");
        sb.append("</ul></div>");

        // Action bank values
        sb.append("<div class='card bank'>");
        sb.append("<div class='card-header'><div class='icon purple'>&#x1F3E6;</div><h2>").append(esc("行动牌银行面值")).append("</h2></div>");
        sb.append("<p>").append(esc("存入银行时按角标 M 计：")).append("</p>");
        sb.append("<table><tr><th>").append(esc("牌名")).append("</th><th>").append(esc("面值")).append("</th></tr>");
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(MonopolyDealBankValues.actionBankValuesUnmodifiable().entrySet());
        entries.sort(Comparator.comparing(Map.Entry::getKey));
        for (Map.Entry<String, Integer> e : entries) {
            sb.append("<tr><td>").append(esc(e.getKey())).append("</td><td><strong>").append(e.getValue()).append("M</strong></td></tr>");
        }
        sb.append("</table></div>");

        // Property values
        sb.append("<div class='card property'>");
        sb.append("<div class='card-header'><div class='icon teal'>&#x1F3E0;</div><h2>").append(esc("房产抵押价值")).append("</h2></div>");
        sb.append("<ul>");
        sb.append("<li>").append(esc("棕色 / 深蓝 / 公共：")).append("<strong>2M</strong></li>");
        sb.append("<li>").append(esc("多数颜色：")).append("<strong>3M</strong></li>");
        sb.append("<li>").append(esc("铁路：")).append("<strong>4M</strong></li>");
        sb.append("</ul></div>");

        // Wild property
        sb.append("<div class='card wild'>");
        sb.append("<div class='card-header'><div class='icon pink'>&#x1F3B4;</div><h2>").append(esc("万能房产")).append("</h2></div>");
        sb.append("<ul>");
        sb.append("<li>").append(esc("声明颜色后不可改色")).append("</li>");
        sb.append("<li>2 ").append(esc("张任意色万能无货币价值")).append("</li>");
        sb.append("<li>9 ").append(esc("张双色万能按牌面角标价值支付")).append("</li>");
        sb.append("</ul></div>");

        // Wizard play
        sb.append("<div class='card play'>");
        sb.append("<div class='card-header'><div class='icon blue'>&#x1FA84;</div><h2>").append(esc("向导化出牌")).append("</h2></div>");
        sb.append("<p>").append(esc("出牌阶段可先发 ")).append("<code>PLAY_OPTIONS</code>").append(esc("（playerId + cardId + actionType），"));
        sb.append(esc("或仅用行动牌发 ")).append("<code>ACTION_OPTIONS</code>").append(esc("；服务器返回选项后再发 ")).append("<code>PLAY</code>").append(esc("。")).append("</p>");
        sb.append("<div class='highlight'>").append(esc("支持的 actionType：DEPOSIT / DEPLOY / DISCARD / ACTION")).append("</div></div>");

        // Rent
        sb.append("<div class='card rent'>");
        sb.append("<div class='card-header'><div class='icon amber'>&#x1F4B3;</div><h2>").append(esc("收租规则")).append("</h2></div>");
        sb.append("<ul>");
        sb.append("<li>").append(esc("拥有该颜色至少 ")).append("<strong>1 ").append(esc("张房产")).append("</strong>").append(esc("（含已声明该色的万能）即可收租")).append("</li>");
        sb.append("<li>").append(esc("基础租按牌面阶梯计算，多套同色分段累计")).append("</li>");
        sb.append("<li>").append(esc("房屋 ")).append("<strong>+3M</strong>").append(esc("，旅馆 ")).append("<strong>+7M</strong></li>");
        sb.append("<li>").append(esc("铁路 / 公共事业不可加盖房 / 旅馆")).append("</li>");
        sb.append("<li>").append(esc("RENT_DUAL 为卡面两色选一，向一名对手收租")).append("</li>");
        sb.append("</ul></div>");

        // Set sizes
        sb.append("<div class='card sets'>");
        sb.append("<div class='card-header'><div class='icon deep'>&#x1F4CA;</div><h2>").append(esc("各色套数需求")).append("</h2></div>");
        sb.append("<div style='display:flex;flex-wrap:wrap;gap:6px;margin-top:8px;'>");
        String[][] colorMapZh = {
            {"BROWN", "棕", "#795548", "white"},
            {"DARK_BLUE", "深蓝", "#1565c0", "white"},
            {"GREEN", "绿", "#43a047", "white"},
            {"LIGHT_BLUE", "浅蓝", "#4fc3f7", "#333"},
            {"ORANGE", "橙", "#ff9800", "white"},
            {"PINK", "粉", "#f06292", "white"},
            {"RAILROAD", "铁路", "#5d4037", "white"},
            {"RED", "红", "#e53935", "white"},
            {"UTILITY", "公共", "#90a4ae", "white"},
            {"YELLOW", "黄", "#fbc02d", "#333"}
        };
        Map<String, Integer> reqMap = PropertySetCalculator.REQUIRED_BY_COLOR;
        for (String[] cm : colorMapZh) {
            Integer req = reqMap.get(cm[0]);
            if (req != null) {
                sb.append("<span class='color-chip' style='background:").append(cm[2])
                    .append(";color:").append(cm[3]).append("'>")
                    .append(esc(cm[1])).append(" ").append(req).append(esc("张")).append("</span>");
            }
        }
        sb.append("</div></div>");

        // Effects
        sb.append("<div class='card effects'>");
        sb.append("<div class='card-header'><div class='icon rose'>&#x26A1;</div><h2>").append(esc("效果金额")).append("</h2></div>");
        sb.append("<ul>");
        sb.append("<li><strong>").append(esc("讨债")).append("</strong>").append(esc("：向目标收 ")).append("<strong>5M</strong></li>");
        sb.append("<li><strong>").append(esc("生日")).append("</strong>").append(esc("：每位其他玩家付 ")).append("<strong>2M</strong></li>");
        sb.append("</ul></div>");

        sb.append("<div class='footer'>Monopoly Deal &middot; ").append(esc("规则摘要")).append("</div>");
        sb.append("</body></html>");
        return sb.toString();
    }

    // ─────────────────── English HTML ───────────────────

    public static String buildHtmlEnglish() {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang='en'><head><meta charset='UTF-8'>").append(CSS).append("</head><body>");

        sb.append("<div class='hero'><h1>&#x1F0CF; Monopoly Deal Rules</h1>");
        sb.append("<p>Complete rules overview &mdash; learn quickly, play confidently</p></div>");

        // Deck
        sb.append("<div class='card'>");
        sb.append("<div class='card-header'><div class='icon blue'>&#x1F0CF;</div><h2>Deck Composition</h2></div>");
        sb.append("<p>This client uses <strong>").append(GameConstants.STANDARD_DECK_SIZE).append(" playable cards</strong> (the 108-card list includes 2 rule cards that are not shuffled):</p>");
        sb.append("<table><tr><th>Type</th><th>Count</th><th>Details</th></tr>");
        sb.append("<tr><td>Property</td><td>28</td><td>Basic property cards of each color</td></tr>");
        sb.append("<tr><td>Wild</td><td>11</td><td>2 any-color + 9 dual-color</td></tr>");
        sb.append("<tr><td>Money</td><td>20</td><td>Various denomination cash cards</td></tr>");
        sb.append("<tr><td>Action</td><td>34</td><td>Pass Go 10, other actions 24</td></tr>");
        sb.append("<tr><td>Rent</td><td>13</td><td>Any Rent 3, dual-color rent 10</td></tr>");
        sb.append("</table></div>");

        // Victory
        sb.append("<div class='card victory'>");
        sb.append("<div class='card-header'><div class='icon yellow'>&#x1F3C6;</div><h2>Victory Condition</h2></div>");
        sb.append("<p>First player to collect <strong>3 complete property sets</strong> of different colors wins.</p></div>");

        // Turn
        sb.append("<div class='card turn'>");
        sb.append("<div class='card-header'><div class='icon green'>&#x1F504;</div><h2>Turn Flow</h2></div>");
        sb.append("<ul>");
        sb.append("<li><strong>Draw</strong>: 2 cards (5 if hand is empty)</li>");
        sb.append("<li><strong>Play</strong>: Up to 3 cards</li>");
        sb.append("<li><strong>Discard</strong>: Down to 7 if over</li>");
        sb.append("</ul></div>");

        // Payment
        sb.append("<div class='card payment'>");
        sb.append("<div class='card-header'><div class='icon red'>&#x1F4B0;</div><h2>Payment Rules</h2></div>");
        sb.append("<ul>");
        sb.append("<li>Pay only from <strong>bank and property zones</strong>, not from hand</li>");
        sb.append("<li>Overpayment is not refunded</li>");
        sb.append("<li>Select specific cards to pay in client; otherwise server auto-selects lowest value first</li>");
        sb.append("</ul></div>");

        // Action bank values
        sb.append("<div class='card bank'>");
        sb.append("<div class='card-header'><div class='icon purple'>&#x1F3E6;</div><h2>Action Card Bank Values</h2></div>");
        sb.append("<p>When deposited to bank, valued at corner M:</p>");
        sb.append("<table><tr><th>Card</th><th>Value</th></tr>");
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(MonopolyDealBankValues.actionBankValuesUnmodifiable().entrySet());
        entries.sort(Comparator.comparing(Map.Entry::getKey));
        for (Map.Entry<String, Integer> e : entries) {
            sb.append("<tr><td>").append(esc(e.getKey())).append("</td><td><strong>").append(e.getValue()).append("M</strong></td></tr>");
        }
        sb.append("</table></div>");

        // Property values
        sb.append("<div class='card property'>");
        sb.append("<div class='card-header'><div class='icon teal'>&#x1F3E0;</div><h2>Property Pledge Values</h2></div>");
        sb.append("<ul>");
        sb.append("<li>Brown / Dark Blue / Utility: <strong>2M</strong></li>");
        sb.append("<li>Most colors: <strong>3M</strong></li>");
        sb.append("<li>Railroad: <strong>4M</strong></li>");
        sb.append("</ul></div>");

        // Wild property
        sb.append("<div class='card wild'>");
        sb.append("<div class='card-header'><div class='icon pink'>&#x1F3B4;</div><h2>Wild Property</h2></div>");
        sb.append("<ul>");
        sb.append("<li>Color is locked after declaration</li>");
        sb.append("<li>2 any-color wilds have no monetary value</li>");
        sb.append("<li>9 printed dual wilds use their card face value</li>");
        sb.append("</ul></div>");

        // Wizard play
        sb.append("<div class='card play'>");
        sb.append("<div class='card-header'><div class='icon blue'>&#x1FA84;</div><h2>Wizard Play</h2></div>");
        sb.append("<p>During play phase, send <code>PLAY_OPTIONS</code> (playerId + cardId + actionType), ");
        sb.append("or <code>ACTION_OPTIONS</code> for action cards only; server returns options, then send <code>PLAY</code>.</p>");
        sb.append("<div class='highlight'>Supported actionType: DEPOSIT / DEPLOY / DISCARD / ACTION</div></div>");

        // Rent
        sb.append("<div class='card rent'>");
        sb.append("<div class='card-header'><div class='icon amber'>&#x1F4B3;</div><h2>Rent Rules</h2></div>");
        sb.append("<ul>");
        sb.append("<li>Own at least <strong>1 property</strong> of that color (including declared wilds) to charge rent</li>");
        sb.append("<li>Base rent follows step table; multiple sets of same color accumulate in tiers</li>");
        sb.append("<li>House <strong>+3M</strong>, Hotel <strong>+7M</strong></li>");
        sb.append("<li>Railroad / Utility cannot have House / Hotel</li>");
        sb.append("<li>RENT_DUAL targets one opponent for one of the two printed colors</li>");
        sb.append("</ul></div>");

        // Set sizes
        sb.append("<div class='card sets'>");
        sb.append("<div class='card-header'><div class='icon deep'>&#x1F4CA;</div><h2>Set Size Requirements</h2></div>");
        sb.append("<div style='display:flex;flex-wrap:wrap;gap:6px;margin-top:8px;'>");
        String[][] colorMapEn = {
            {"BROWN", "Brown", "#795548", "white"},
            {"DARK_BLUE", "Dk Blue", "#1565c0", "white"},
            {"GREEN", "Green", "#43a047", "white"},
            {"LIGHT_BLUE", "Lt Blue", "#4fc3f7", "#333"},
            {"ORANGE", "Orange", "#ff9800", "white"},
            {"PINK", "Pink", "#f06292", "white"},
            {"RAILROAD", "Railroad", "#5d4037", "white"},
            {"RED", "Red", "#e53935", "white"},
            {"UTILITY", "Utility", "#90a4ae", "white"},
            {"YELLOW", "Yellow", "#fbc02d", "#333"}
        };
        Map<String, Integer> reqMap = PropertySetCalculator.REQUIRED_BY_COLOR;
        for (String[] cm : colorMapEn) {
            Integer req = reqMap.get(cm[0]);
            if (req != null) {
                sb.append("<span class='color-chip' style='background:").append(cm[2])
                    .append(";color:").append(cm[3]).append("'>")
                    .append(cm[1]).append(" ").append(req).append("</span>");
            }
        }
        sb.append("</div></div>");

        // Effects
        sb.append("<div class='card effects'>");
        sb.append("<div class='card-header'><div class='icon rose'>&#x26A1;</div><h2>Effect Amounts</h2></div>");
        sb.append("<ul>");
        sb.append("<li><strong>Debt Collector</strong>: collect <strong>5M</strong> from target</li>");
        sb.append("<li><strong>Birthday</strong>: each other player pays <strong>2M</strong></li>");
        sb.append("</ul></div>");

        sb.append("<div class='footer'>Monopoly Deal &middot; Rules Summary</div>");
        sb.append("</body></html>");
        return sb.toString();
    }

    // ─────────────────── Plain Text (fallback) ───────────────────

    public static String buildPlainTextChinese() {
        StringBuilder sb = new StringBuilder();
        sb.append("【牌堆】本客户端使用 ").append(GameConstants.STANDARD_DECK_SIZE)
                .append(" 张可游戏牌（108 张清单中 2 张规则卡不入牌堆）：房产 28、万能 11（2 任意色 + 9 印定双色）、钱币 20、行动 34、租金 13。\n\n");
        sb.append("【胜利】先凑齐 3 套不同颜色的完整房产集。\n\n");
        sb.append("【回合】摸 2 张（手牌为空则摸 5）；最多打出 3 张；手牌超过 7 张须弃至 7。\n\n");
        sb.append("【支付】仅能从银行区与财产区支付，不能从手牌直接支付；多付不退。\n\n");
        sb.append("【行动牌银行面值】存入银行时按角标 M 计：\n");
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(MonopolyDealBankValues.actionBankValuesUnmodifiable().entrySet());
        entries.sort(Comparator.comparing(Map.Entry::getKey));
        for (Map.Entry<String, Integer> e : entries) {
            sb.append("  \u00B7 ").append(e.getKey()).append(" \u2192 ").append(e.getValue()).append("M\n");
        }
        sb.append("\n【房产抵押价值】棕色/深蓝/公共 2M；多数颜色 3M；铁路 4M。\n\n");
        sb.append("【万能房产】声明颜色后不可改色。2 张任意色万能无货币价值；9 张双色万能按牌面角标价值支付（见手牌 JSON wildKind / printedColors / valueM）。\n\n");

        sb.append("【向导化出牌】出牌阶段可先发 `PLAY_OPTIONS`（playerId + cardId + actionType：DEPOSIT/DEPLOY/DISCARD/ACTION），")
                .append("或仅用行动牌发 `ACTION_OPTIONS`；服务器返回选项后再 `PLAY`。\n\n");

        sb.append("【收租】拥有该颜色至少 1 张房产（含已声明该色的万能）即可收租；基础租按实体 Monopoly Deal 牌面阶梯（见 RentTierTable），")
                .append("多套同色分段累计；再加各张房屋 +3M、旅馆 +7M。铁路/公共事业不可加盖房/旅馆。")
                .append(" 默认牌堆中 RENT_DUAL 为卡面两色选一向一名对手收租；房规可启用「全员依次」双色租（见代码标记）。\n\n");
        sb.append("【套数需求】");
        PropertySetCalculator.REQUIRED_BY_COLOR.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> sb.append(e.getKey()).append("=").append(e.getValue()).append("张 "));
        sb.append("\n\n【效果金额】讨债向目标收 5M；生日每位其他玩家付 2M。\n");
        return sb.toString();
    }

    public static String buildPlainTextEnglish() {
        StringBuilder sb = new StringBuilder();
        sb.append("[DECK] This client uses ").append(GameConstants.STANDARD_DECK_SIZE)
                .append(" playable cards; the 108-card list includes 2 rule cards that are not shuffled.\n\n");
        sb.append("[VICTORY] First to collect 3 complete property sets of different colors wins.\n\n");
        sb.append("[TURN] Draw 2 (draw 5 if hand is empty); play up to 3 cards; discard down to 7 if over.\n\n");
        sb.append("[PAYMENT] Pay only from bank and property zones; overpayment is not refunded.\n\n");
        sb.append("[ACTION CARD BANK VALUE] When deposited to bank, valued at corner M:\n");
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(MonopolyDealBankValues.actionBankValuesUnmodifiable().entrySet());
        entries.sort(Comparator.comparing(Map.Entry::getKey));
        for (Map.Entry<String, Integer> e : entries) {
            sb.append("  - ").append(e.getKey()).append(" -> ").append(e.getValue()).append("M\n");
        }
        sb.append("\n[PROPERTY VALUE] Brown/DarkBlue/Utility 2M; most colors 3M; Railroad 4M.\n\n");
        sb.append("[WILD PROPERTY] Color is locked after declaration. Any-color wilds have no monetary value; printed dual wilds use their card face value.\n\n");
        sb.append("[WIZARD PLAY] During play phase, send PLAY_OPTIONS (playerId + cardId + actionType: DEPOSIT/DEPLOY/DISCARD/ACTION), ")
                .append("or ACTION_OPTIONS for action cards; send PLAY after the server returns options.\n\n");
        sb.append("[RENT] Own at least 1 property of the chosen color (including declared wilds) to charge rent; ")
                .append("base rent follows RentTierTable and multiple same-color sets accumulate by tiers. ")
                .append("House +3M, Hotel +7M. Railroad/Utility cannot have House/Hotel. ")
                .append("Default RENT_DUAL picks one of the two printed colors and targets one opponent.\n\n");
        sb.append("[SET SIZE] ");
        PropertySetCalculator.REQUIRED_BY_COLOR.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> sb.append(e.getKey()).append("=").append(e.getValue()).append(" "));
        sb.append("\n\n[EFFECT AMOUNTS] Debt Collector: 5M; Birthday: each other player pays 2M.\n");
        return sb.toString();
    }
}

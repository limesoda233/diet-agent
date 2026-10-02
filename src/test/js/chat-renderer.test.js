const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const rendererPath = path.resolve(__dirname,
    "../../main/resources/static/assets/js/chat-renderer.js");
const renderer = require(rendererPath);

test("chat renderer is shipped as an independently testable browser module", () => {
    assert.equal(fs.existsSync(rendererPath), true, "chat-renderer.js should exist");
});

test("recipe markdown renders headings, lists, emphasis and source links", () => {
    assert.equal(typeof renderer.renderMarkdown, "function");

    const html = renderer.renderMarkdown(`## 番茄炒蛋 [S1]

### 食材
- **番茄** 2 个 [S1]
- 鸡蛋 3 个 [S1]

### 步骤
1. 炒熟鸡蛋 [S1]
2. 加入番茄 [S1]

### 来源
- [原始菜谱](https://example.com/recipe)`);

    assert.match(html, /<h2>番茄炒蛋 \[S1\]<\/h2>/);
    assert.match(html, /<ul>[\s\S]*<strong>番茄<\/strong>[\s\S]*<\/ul>/);
    assert.match(html, /<ol>[\s\S]*炒熟鸡蛋[\s\S]*<\/ol>/);
    assert.match(html, /target="_blank"/);
    assert.match(html, /rel="noopener noreferrer"/);
});

test("markdown rendering escapes raw html and unsafe links", () => {
    assert.equal(typeof renderer.renderMarkdown, "function");

    const html = renderer.renderMarkdown("<img src=x onerror=alert(1)> [危险](javascript:alert(1))");

    assert.doesNotMatch(html, /<img/);
    assert.doesNotMatch(html, /href="javascript:/);
    assert.match(html, /&lt;img/);
});

test("video cards are clickable, descriptive and open safely in a new window", () => {
    assert.equal(typeof renderer.renderVideoCards, "function");

    const html = renderer.renderVideoCards([{
        title: "番茄炒蛋教程",
        url: "https://www.bilibili.com/video/BV1abc",
        platform: "BILIBILI",
        creator: "厨房阿姨",
        durationSeconds: 332
    }]);

    assert.match(html, /class="video-card"/);
    assert.match(html, /href="https:\/\/www\.bilibili\.com\/video\/BV1abc"/);
    assert.match(html, /target="_blank"/);
    assert.match(html, /rel="noopener noreferrer"/);
    assert.match(html, /番茄炒蛋教程/);
    assert.match(html, /厨房阿姨/);
    assert.match(html, /05:32/);
    assert.match(html, /打开视频/);
});

test("video cards reject non-Bilibili destinations", () => {
    const html = renderer.renderVideoCards([{
        title: "伪造视频",
        url: "https://example.com/video/BV1abc",
        platform: "BILIBILI",
        creator: "unknown",
        durationSeconds: 30
    }]);

    assert.equal(html, "");
});

test("chat page uses the renderer for assistant markdown and structured videos", () => {
    const staticRoot = path.resolve(path.dirname(rendererPath), "../..");
    const index = fs.readFileSync(path.join(staticRoot, "index.html"), "utf8");
    const app = fs.readFileSync(path.join(staticRoot, "assets/js/app.js"), "utf8");

    assert.ok(index.indexOf("chat-renderer.js") < index.indexOf("app.js"));
    assert.match(app, /DietChatRenderer\.renderMarkdown\(message\.text\)/);
    assert.match(app, /DietChatRenderer\.renderVideoCards\(message\.videos\)/);
    assert.match(app, /videos:\s*response\.videoBlocks/);
});

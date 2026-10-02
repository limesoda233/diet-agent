(function (root, factory) {
    "use strict";
    const renderer = factory();
    if (typeof module === "object" && module.exports) {
        module.exports = renderer;
    }
    root.DietChatRenderer = renderer;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
    "use strict";

    function escapeHtml(value) {
        return String(value ?? "")
            .replaceAll("&", "&amp;")
            .replaceAll("<", "&lt;")
            .replaceAll(">", "&gt;")
            .replaceAll('"', "&quot;")
            .replaceAll("'", "&#039;");
    }

    function safeExternalUrl(value) {
        try {
            const url = new URL(String(value));
            return url.protocol === "http:" || url.protocol === "https:" ? url.href : null;
        } catch (error) {
            return null;
        }
    }

    function renderInline(value) {
        const links = [];
        const tokenized = String(value ?? "").replace(/\[([^\]]+)]\(([^\s)]+)\)/g,
            (whole, label, href) => {
                const safeHref = safeExternalUrl(href);
                if (!safeHref) return whole;
                const token = `@@DIET_LINK_${links.length}@@`;
                links.push(`<a href="${escapeHtml(safeHref)}" target="_blank" rel="noopener noreferrer">${escapeHtml(label)}</a>`);
                return token;
            });
        let html = escapeHtml(tokenized)
            .replace(/`([^`]+)`/g, "<code>$1</code>")
            .replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>");
        links.forEach((link, index) => {
            html = html.replace(`@@DIET_LINK_${index}@@`, link);
        });
        return html;
    }

    function renderMarkdown(value) {
        const lines = String(value ?? "").replaceAll("\r", "").split("\n");
        const output = [];
        let paragraph = [];
        let listType = null;

        function flushParagraph() {
            if (!paragraph.length) return;
            output.push(`<p>${paragraph.map(renderInline).join("<br>")}</p>`);
            paragraph = [];
        }

        function closeList() {
            if (!listType) return;
            output.push(`</${listType}>`);
            listType = null;
        }

        function appendListItem(type, content) {
            flushParagraph();
            if (listType !== type) {
                closeList();
                output.push(`<${type}>`);
                listType = type;
            }
            output.push(`<li>${renderInline(content)}</li>`);
        }

        lines.forEach((line) => {
            const trimmed = line.trim();
            if (!trimmed) {
                flushParagraph();
                closeList();
                return;
            }
            const heading = trimmed.match(/^(#{1,3})\s+(.+)$/);
            if (heading) {
                flushParagraph();
                closeList();
                const level = heading[1].length;
                output.push(`<h${level}>${renderInline(heading[2])}</h${level}>`);
                return;
            }
            const unordered = trimmed.match(/^[-*]\s+(.+)$/);
            if (unordered) {
                appendListItem("ul", unordered[1]);
                return;
            }
            const ordered = trimmed.match(/^\d+\.\s+(.+)$/);
            if (ordered) {
                appendListItem("ol", ordered[1]);
                return;
            }
            closeList();
            paragraph.push(trimmed);
        });
        flushParagraph();
        closeList();
        return output.join("");
    }

    function formatDuration(totalSeconds) {
        const seconds = Number(totalSeconds);
        if (!Number.isFinite(seconds) || seconds < 0) return "";
        const whole = Math.floor(seconds);
        const hours = Math.floor(whole / 3600);
        const minutes = Math.floor((whole % 3600) / 60);
        const remaining = whole % 60;
        const pad = (value) => String(value).padStart(2, "0");
        return hours > 0
            ? `${hours}:${pad(minutes)}:${pad(remaining)}`
            : `${pad(minutes)}:${pad(remaining)}`;
    }

    function platformLabel(value) {
        return String(value ?? "").toUpperCase() === "BILIBILI" ? "哔哩哔哩" : String(value ?? "视频");
    }

    function safeBilibiliVideoUrl(value) {
        const safeUrl = safeExternalUrl(value);
        if (!safeUrl) return null;
        const url = new URL(safeUrl);
        const allowedHost = url.hostname === "www.bilibili.com" || url.hostname === "bilibili.com";
        return url.protocol === "https:" && allowedHost && /^\/video\/BV[0-9A-Za-z]+\/?$/.test(url.pathname)
            ? url.href : null;
    }

    function renderVideoCards(videos) {
        const cards = (Array.isArray(videos) ? videos : []).map((video) => {
            const href = safeBilibiliVideoUrl(video && video.url);
            if (!href) return "";
            const title = escapeHtml(video.title || "做饭教学视频");
            const creator = escapeHtml(video.creator || "未知创作者");
            const platform = escapeHtml(platformLabel(video.platform));
            const duration = formatDuration(video.durationSeconds);
            const meta = duration ? `${creator}<span aria-hidden="true">·</span>${escapeHtml(duration)}` : creator;
            return `
                <a class="video-card" href="${escapeHtml(href)}" target="_blank" rel="noopener noreferrer"
                   aria-label="打开视频：${title}">
                    <span class="video-card-platform"><span class="video-play" aria-hidden="true">▶</span>${platform}</span>
                    <strong>${title}</strong>
                    <span class="video-card-meta">${meta}</span>
                    <span class="video-card-action">打开视频 <span aria-hidden="true">↗</span></span>
                </a>`;
        }).filter(Boolean);
        return cards.length ? `<div class="video-card-grid">${cards.join("")}</div>` : "";
    }

    return { renderMarkdown, renderVideoCards };
});

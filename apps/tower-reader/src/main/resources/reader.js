(function () {
"use strict";
var content = document.getElementById("content"), viewport = document.getElementById("viewport");
var nodes = [], starts = [], length = 0, page = 0, count = 1, columns = 1, stride = 1;
var anchor = 0, settings = null, resizeTimer = null, stopped = false, hostKeyboard = false;
function indexText() {
    var walker = document.createTreeWalker(content, NodeFilter.SHOW_TEXT | NodeFilter.SHOW_ELEMENT, null, false), n;
    nodes = []; starts = []; length = 0;
    while ((n = walker.nextNode())) {
        if (n.nodeType === 3 || n.tagName === "IMG") { nodes.push(n); starts.push(length); length += n.nodeType === 3 ? n.length : 1; }
    }
}
// Geometry is separate from the raw text index: existing saved offsets must not change.
var measured = [], viewportLeft = 0;
function visibleRect(rect) { return rect.width > 0 && rect.height > 0; }
function rectColumn(rect) {
    return Math.max(0, Math.floor((rect.left - viewportLeft + page * stride + 0.5) / stride));
}
function measureContent() {
    measured = []; viewportLeft = viewport.getBoundingClientRect().left;
    var maxCol = 0, range = document.createRange();
    for (var i = 0; i < nodes.length; i++) {
        var node = nodes[i], rects;
        if (node.nodeType === 3) { range.selectNodeContents(node); rects = range.getClientRects(); }
        else rects = node.getClientRects();
        var first = Infinity, last = -1;
        for (var j = 0; j < rects.length; j++) if (visibleRect(rects[j])) {
            var col = rectColumn(rects[j]); first = Math.min(first, col); last = Math.max(last, col);
        }
        if (last >= 0) {
            measured.push({node: node, start: starts[i], end: starts[i] + (node.nodeType === 3 ? node.length : 1), first: first, last: last});
            maxCol = Math.max(maxCol, last);
        }
    }
    count = maxCol + 1;
}
function columnIn(item, offset) {
    if (item.node.nodeType !== 3) return item.first;
    var at = Math.max(0, Math.min(item.node.length - 1, offset - item.start));
    var range = document.createRange(); range.setStart(item.node, at); range.setEnd(item.node, at + 1);
    var rect = range.getBoundingClientRect();
    if (visibleRect(rect)) return rectColumn(rect);
    // Collapsed spaces have empty character rectangles. The visible prefix gives a
    // monotonic column for binary search without scanning every character.
    range.setStart(item.node, 0);
    var rects = range.getClientRects();
    for (var i = rects.length - 1; i >= 0; i--) if (visibleRect(rects[i])) return rectColumn(rects[i]);
    return item.first;
}
function colAt(at) {
    if (!measured.length) return 0;
    var low = 0, high = measured.length - 1;
    while (low < high) { var mid = Math.floor((low + high) / 2); if (measured[mid].end <= at) low = mid + 1; else high = mid; }
    var item = measured[low];
    if (at < item.start) return item.first;
    if (at >= item.end) return item.last;
    return columnIn(item, at);
}
function offsetAt(col) {
    if (!measured.length) return 0;
    var low = 0, high = measured.length - 1;
    while (low < high) { var mid = Math.floor((low + high) / 2); if (measured[mid].last < col) low = mid + 1; else high = mid; }
    var item = measured[low]; low = item.start; high = item.end - 1;
    while (low < high) {
        var m = Math.floor((low + high) / 2);
        if (columnIn(item, m) < col) low = m + 1; else high = m;
    }
    return low;
}
function report() {
    if (window.readerHost && !stopped) readerHost.updated(Math.round(anchor), page, count, columns, length);
}
function position(p, keepAnchor) {
    page = Math.max(0, Math.min(Math.max(0, count - 1), p));
    page = Math.floor(page / columns) * columns;
    content.style.transform = "translateX(" + (-page * stride) + "px)";
    if (!keepAnchor) anchor = offsetAt(page);
    report();
}
function layout(at) {
    if (!settings || stopped) return;
    anchor = Math.max(0, Math.min(length, at));
    var w = window.innerWidth, h = window.innerHeight, margin = settings.margin;
    columns = settings.spread === "double" ? (w >= 620 ? 2 : 1) : settings.spread === "auto" && w >= 1080 ? 2 : 1;
    var width = Math.max(200, Math.min(w - 2 * margin, columns === 2 ? 1660 : 860));
    var height = Math.max(100, h - 2 * Math.min(margin, 48));
    var gap = columns === 2 ? 56 : 36;
    var columnWidth = Math.floor((width - (columns - 1) * gap) / columns);
    width = columnWidth * columns + gap * (columns - 1);
    stride = columnWidth + gap;
    viewport.style.width = width + "px"; viewport.style.height = height + "px";
    viewport.style.left = Math.max(0, (w - width) / 2) + "px"; viewport.style.top = Math.min(margin, 48) + "px";
    content.style.width = width + "px"; content.style.height = height + "px";
    content.style.columnCount = columns; content.style.columnGap = gap + "px";
    content.style.columnWidth = columnWidth + "px"; content.style.transform = "none"; page = 0;
    document.documentElement.style.setProperty("--page-height", height + "px");
    var pictures = content.querySelectorAll("img");
    for (var j = 0; j < pictures.length; j++) {
        var picture = pictures[j], nw = picture.naturalWidth, nh = picture.naturalHeight;
        if (nw && nh) {
            var scale = Math.min(1, columnWidth / nw, (height - 24) / nh);
            picture.style.width = Math.max(1, Math.floor(nw * scale)) + "px";
            picture.style.height = Math.max(1, Math.floor(nh * scale)) + "px";
        }
    }
    measureContent();
    var target = anchor >= length && length ? count - 1 : colAt(anchor);
    position(target, true);
}
window.Reader = {
    useHostKeyboard: function (enabled) { hostKeyboard = enabled; },
    init: function (s, at) {
        indexText(); this.settings(s, at);
        var images = content.querySelectorAll("img");
        for (var i = 0; i < images.length; i++) images[i].addEventListener("load", function () { layout(anchor); });
        if (document.fonts && document.fonts.ready) document.fonts.ready.then(function () { layout(anchor); });
    },
    settings: function (s, at) {
        settings = s;
        document.body.style.background = s.background; document.body.style.color = s.foreground;
        content.style.fontFamily = '"' + s.font.replace(/["\\]/g, "") + '", "Microsoft YaHei", sans-serif';
        content.style.fontSize = s.size + "px"; content.style.lineHeight = s.line;
        layout(typeof at === "number" ? at : anchor);
    },
    turn: function (direction) {
        if (direction > 0 && page + columns >= count) { if (window.readerHost) readerHost.boundary(1); return; }
        if (direction < 0 && page === 0) { if (window.readerHost) readerHost.boundary(-1); return; }
        position(page + direction * columns, false);
    },
    jump: function (at) { layout(at); },
    fragment: function (id) {
        var element = document.getElementById(id); if (!element) return;
        for (var i = 0; i < nodes.length; i++) {
            if (nodes[i] === element || element.contains(nodes[i])) { layout(starts[i]); return; }
        }
    },
    state: function () { return [anchor, page, count, columns, length].join(","); },
    stop: function () { stopped = true; if (resizeTimer) clearTimeout(resizeTimer); }
};
window.addEventListener("resize", function () {
    if (resizeTimer) clearTimeout(resizeTimer);
    resizeTimer = setTimeout(function () { layout(anchor); }, 140);
});
document.addEventListener("click", function (e) {
    var a = e.target; while (a && a.tagName !== "A") a = a.parentElement;
    if (a) { e.preventDefault(); if (window.readerHost) readerHost.link(a.getAttribute("href") || ""); }
});
document.addEventListener("keydown", function (e) {
    if (hostKeyboard || e.ctrlKey || e.altKey || e.metaKey) return;
    var direction = 0;
    if (e.key === "ArrowRight" || e.key === "PageDown" || e.key === " " && !e.shiftKey) direction = 1;
    if (e.key === "ArrowLeft" || e.key === "PageUp" || e.key === " " && e.shiftKey) direction = -1;
    if (direction) { e.preventDefault(); Reader.turn(direction); }
    if (e.key === "F11" || e.key === "Escape") { e.preventDefault(); if (window.readerHost) readerHost.action(e.key); }
});
})();

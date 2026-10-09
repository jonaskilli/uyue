package io.legado.app.help.video

/**
 * 书源播放器页面的文案本地化脚本（仅替换显示文字）。
 *
 * 很多视频/短剧书源的播放器是英文界面，例如：
 * `<div class="btn-select-episode">Episodes</div>`、选集弹窗标题 `Episodes` 等。
 * 播放功能本身没问题时，没必要为了改文案去动书源，
 * 这里只遍历文本节点做一次中译，不触碰任何元素属性、事件与播放器逻辑。
 *
 * 安全边界：
 * - 只处理文本节点（nodeType === 3），不改 class / id / onclick / src；
 * - 跳过 script / style / textarea，避免破坏页面脚本与样式；
 * - 选集弹窗多为点击后动态生成，故用 MutationObserver 持续兜底并设置防抖与超时。
 */
object VideoTextLocalizeJs {

    /** 返回 "ok" 表示已注入，"skip" 表示页面尚未就绪 */
    const val SCRIPT = """
(function () {
    try {
        if (!document.body) return 'skip';

        var MAP = [
            ['Episodes', '选集'],
            ['Episode', '集'],
            ['Playlist', '播放列表'],
            ['Seasons', '季'],
            ['Season', '季']
        ];

        function translate(text) {
            var out = text;
            for (var i = 0; i < MAP.length; i++) {
                // 整词匹配，避免把 Player 之类的单词切坏
                out = out.replace(new RegExp('\\b' + MAP[i][0] + '\\b', 'g'), MAP[i][1]);
            }
            return out;
        }

        function walk(node) {
            if (!node) return;
            if (node.nodeType === 3) {
                var t = node.nodeValue;
                if (t && t.trim().length > 0) {
                    var nt = translate(t);
                    if (nt !== t) node.nodeValue = nt;
                }
                return;
            }
            if (node.nodeType !== 1) return;
            var tag = node.tagName;
            // 跳过脚本与样式，避免破坏页面逻辑
            if (tag === 'SCRIPT' || tag === 'STYLE' || tag === 'NOSCRIPT' || tag === 'TEXTAREA') return;
            var children = node.childNodes;
            for (var i = 0; i < children.length; i++) walk(children[i]);
        }

        function run() {
            if (document.body) walk(document.body);
        }

        run();

        // 选集弹窗等常在点击后才生成，用观察器兜底；修改文本会再次触发，故先断开再重连
        if (window.MutationObserver && !window.__legadoLocalized) {
            window.__legadoLocalized = true;
            var timer = null;
            var observer = new MutationObserver(function () {
                if (timer) return;
                timer = setTimeout(function () {
                    timer = null;
                    observer.disconnect();
                    run();
                    observer.observe(document.body, {
                        childList: true, subtree: true, characterData: true
                    });
                }, 150);
            });
            observer.observe(document.body, {
                childList: true, subtree: true, characterData: true
            });
            // 限定观察时长，避免长期空转
            setTimeout(function () {
                observer.disconnect();
                window.__legadoLocalized = false;
            }, 60000);
        }
        return 'ok';
    } catch (e) {
        return 'skip';
    }
})()
"""
}

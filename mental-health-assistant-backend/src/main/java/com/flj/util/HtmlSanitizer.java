package com.flj.util;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;

/**
 * 富文本 HTML 白名单清洗：入库前统一清洗（单一卡点，覆盖所有读取方）。
 * 标签集对齐 wangEditor 产物（p/h1-h6/strong/em/u/blockquote/ul/ol/li/a/span/img），
 * 剥离 script/iframe/事件属性（on*）等；img 仅允许 http(s) 来源。
 */
public final class HtmlSanitizer {

    private HtmlSanitizer() {
    }

    private static final Safelist SAFELIST = Safelist.basicWithImages()
            .addTags("h1", "h2", "h3", "h4", "h5", "h6")
            // wangEditor 以 span 的内联 style 承载颜色/字号/字体
            .addAttributes("span", "style")
            .addAttributes("a", "title");

    // 关闭 prettyPrint，避免 Jsoup 重排 HTML 引入换行
    private static final Document.OutputSettings OUTPUT_SETTINGS = new Document.OutputSettings().prettyPrint(false);

    public static String sanitize(String html) {
        if (html == null || html.isEmpty()) {
            return html;
        }
        return Jsoup.clean(html, "", SAFELIST, OUTPUT_SETTINGS);
    }
}

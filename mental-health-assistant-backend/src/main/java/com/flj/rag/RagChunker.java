package com.flj.rag;

import com.flj.util.HtmlSanitizer;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class RagChunker {
    private static final int MAX_HTML_LENGTH = 100000;
    private static final int MAX_CHUNKS = 512;
    private static final int MAX_SECTION_LENGTH = 255;
    private static final Set<String> HEADINGS = Set.of("h1", "h2", "h3", "h4", "h5", "h6");
    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\p{Z}]+");

    public List<IndexChunk> chunk(ClaimedJob claimed) {
        IndexConfig config = claimed.config();
        if (!RagIndexSettings.EXTRACTOR_VERSION.equals(config.extractorVersion())
                || !RagIndexSettings.CHUNKER_VERSION.equals(config.chunkerVersion())
                || config.chunkSize() < 400 || config.chunkSize() > 800
                || config.chunkOverlap() < 80 || config.chunkOverlap() > 120) {
            throw new RagIndexException("503", "RAG_CHUNK_CONFIGURATION", "索引分块配置不受支持", false);
        }
        String html = claimed.content();
        if (html == null || html.length() > MAX_HTML_LENGTH) {
            throw new RagIndexException("400", "RAG_ARTICLE_SIZE", "文章正文超出索引范围", false);
        }
        List<IndexChunk> chunks = new ArrayList<>();
        for (Section section : sections(HtmlSanitizer.sanitize(html))) {
            int[] points = String.join("\n\n", section.paragraphs()).codePoints().toArray();
            int start = 0;
            while (start < points.length) {
                int end = Math.min(start + config.chunkSize(), points.length);
                if (end < points.length) {
                    end = naturalEnd(points, start, end);
                }
                String content = new String(points, start, end - start).strip();
                if (!content.isEmpty()) {
                    if (chunks.size() == MAX_CHUNKS) {
                        throw new RagIndexException("400", "RAG_ARTICLE_CHUNK_LIMIT", "文章分块数量超出索引范围", false);
                    }
                    chunks.add(IndexChunk.create(claimed.job(), chunks.size(), section.heading(), content));
                }
                if (end == points.length) break;
                start = end - config.chunkOverlap();
            }
        }
        if (chunks.isEmpty()) {
            throw new RagIndexException("400", "RAG_ARTICLE_NO_TEXT", "文章没有可索引的正文", false);
        }
        return List.copyOf(chunks);
    }

    private static int naturalEnd(int[] points, int start, int limit) {
        int minimum = start + Math.min(400, limit - start - 1);
        for (int index = limit; index >= minimum; index--) {
            if (points[index] == '\n' && index + 1 < points.length && points[index + 1] == '\n') {
                return index;
            }
        }
        for (int index = limit; index >= minimum; index--) {
            int last = points[index - 1];
            if (last == '。' || last == '！' || last == '？' || last == '；' || last == '!' || last == '?') {
                return index;
            }
        }
        return limit;
    }

    private static List<Section> sections(String html) {
        Element body = Jsoup.parseBodyFragment(html).body();
        List<Section> sections = new ArrayList<>();
        NodeTraversor.traverse(new NodeVisitor() {
            private final StringBuilder paragraph = new StringBuilder();
            private Section current = addSection("");
            private int headingDepth = -1;

            @Override
            public void head(Node node, int depth) {
                if (headingDepth >= 0) return;
                if (node instanceof Element element) {
                    if (HEADINGS.contains(element.normalName())) {
                        flush();
                        current = addSection(sectionTitle(element.text()));
                        headingDepth = depth;
                    } else if (element != body && (element.tag().isBlock() || element.normalName().equals("br"))) {
                        flush();
                    }
                } else if (node instanceof TextNode text) {
                    paragraph.append(text.getWholeText());
                }
            }

            @Override
            public void tail(Node node, int depth) {
                if (headingDepth >= 0) {
                    if (headingDepth == depth) headingDepth = -1;
                    return;
                }
                if (node instanceof Element element && element.tag().isBlock()) flush();
            }

            private Section addSection(String heading) {
                Section section = new Section(heading, new ArrayList<>());
                sections.add(section);
                return section;
            }

            private void flush() {
                String text = WHITESPACE.matcher(paragraph).replaceAll(" ").strip();
                paragraph.setLength(0);
                if (!text.isEmpty()) current.paragraphs().add(text);
            }
        }, body);
        return sections;
    }

    private static String sectionTitle(String title) {
        String plain = WHITESPACE.matcher(title).replaceAll(" ").strip();
        int length = plain.codePointCount(0, plain.length());
        return length <= MAX_SECTION_LENGTH ? plain : plain.substring(0, plain.offsetByCodePoints(0, MAX_SECTION_LENGTH));
    }

    private record Section(String heading, List<String> paragraphs) { }
}

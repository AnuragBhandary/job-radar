package com.anuragbhandary.jobradar.apply.form;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.Margin;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

/**
 * Prints resume HTML to a PDF file.
 *
 * <p>Its own class, and its own headless browser, because it is the one piece of
 * the browser layer with nothing to do with forms. Opening a browser purely to
 * print costs about a second; a second dependency to avoid that would cost more.
 */
@Component
public class PdfWriter {

    public void write(String html, Path destination) {
        try (BrowserSession session = BrowserSession.headless()) {
            Page page = session.newPage();
            // setContent rather than a file:// navigation: no temporary HTML file
            // to write, and nothing left on disk if this throws.
            page.setContent(html);
            page.pdf(new Page.PdfOptions()
                    .setPath(destination)
                    .setFormat("A4")
                    // The stylesheet's @page rule sets the real margins. These are
                    // zero so the two do not add up - Chromium applies both.
                    .setMargin(new Margin().setTop("0").setBottom("0")
                            .setLeft("0").setRight("0"))
                    .setPrintBackground(true)
                    // Without this Chromium prints "about:blank" and a page
                    // number into the margin of the resume.
                    .setDisplayHeaderFooter(false));
        }
    }
}

package com.anuragbhandary.jobradar.resume;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.Margin;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

/**
 * Prints resume HTML to an A4 PDF with headless Chromium.
 *
 * <p>The only reason Playwright is still a dependency. A fresh browser with no
 * profile, so nothing here can touch the persistent profile the earlier
 * form-filling code signed in with.
 */
@Component
public class PdfPrinter {

    public void print(String html, Path destination) {
        try (Playwright playwright = Playwright.create();
                Browser browser = playwright.chromium().launch(
                        new BrowserType.LaunchOptions().setHeadless(true))) {
            Page page = browser.newPage();
            // setContent rather than a file:// navigation: no temporary HTML file.
            page.setContent(html);
            page.pdf(new Page.PdfOptions()
                    .setPath(destination)
                    .setFormat("A4")
                    // The stylesheet's @page rule sets the real margins. These are
                    // zero so the two do not add up; Chromium applies both.
                    .setMargin(new Margin().setTop("0").setBottom("0")
                            .setLeft("0").setRight("0"))
                    .setPrintBackground(true)
                    // Otherwise Chromium prints "about:blank" and a page number.
                    .setDisplayHeaderFooter(false));
        }
    }
}

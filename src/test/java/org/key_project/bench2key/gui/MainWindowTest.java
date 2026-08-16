package org.key_project.bench2key.gui;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Checks that the window builds and lays out.
 *
 * With {@code -Dbench2key.screenshot=<file>} it also paints the window into an image, which is the
 * only way to look at the layout without a person in front of the screen.
 */
class MainWindowTest {

    @Test
    void buildsAndLaysOut() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display");

        AtomicReference<MainWindow> frame = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> frame.set(new MainWindow()));
        MainWindow f = frame.get();
        assertNotNull(f);
        assertTrue(f.getWidth() > 600, "window is too narrow to be usable: " + f.getWidth());
        assertTrue(f.getHeight() > 400, "window is too short to be usable: " + f.getHeight());

        String screenshot = System.getProperty("bench2key.screenshot");
        if (screenshot != null) {
            SwingUtilities.invokeAndWait(() -> {
                // A window has to be on screen before it paints anything, but one that takes the
                // focus interrupts whoever is running the tests. A window that declines the focus
                // is shown without becoming the active one.
                f.setFocusableWindowState(false);
                f.setLocation(-4000, -4000);
                f.setVisible(true);
                javax.swing.JTabbedPane tabs =
                    (javax.swing.JTabbedPane) f.getContentPane().getComponent(0);
                try {
                    // One image per tab, since each one lays itself out for its own language.
                    for (int i = 0; i < tabs.getTabCount(); i++) {
                        tabs.setSelectedIndex(i);
                        f.validate();
                        BufferedImage image = new BufferedImage(f.getWidth(), f.getHeight(),
                            BufferedImage.TYPE_INT_ARGB);
                        f.printAll(image.getGraphics());
                        ImageIO.write(image, "png",
                            new File(screenshot.replace(".png", "-" + tabs.getTitleAt(i) + ".png")));
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                f.setVisible(false);
            });
        }
        SwingUtilities.invokeAndWait(f::dispose);
    }
}

package org.wikibrain.webapi;

import EDU.oswego.cs.dl.util.concurrent.FJTask;
import com.google.common.collect.Lists;
import com.vividsolutions.jts.geom.*;
import org.eclipse.jetty.util.ConcurrentHashSet;
import org.jooq.util.derby.sys.Sys;
import org.json.JSONObject;
import org.json.JSONArray;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.OutputType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wikibrain.conf.ConfigurationException;
import org.wikibrain.core.cmd.Env;
import org.wikibrain.core.dao.DaoException;
import org.wikibrain.core.lang.Language;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.Semaphore;

import org.openqa.selenium.firefox.FirefoxBinary;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.wikibrain.utils.ParallelForEach;
import org.wikibrain.utils.Procedure;
import org.wikibrain.utils.WpThreadUtils;
import sun.rmi.runtime.Log;

/**
 * Created by Josh on 4/7/16.
 */
public class CompariFactReferenceMap implements CompariFactDataSource {
    private static final Logger LOG = LoggerFactory.getLogger(CompariFactReferenceMap.class);
    private enum MapStyle {
        STREETS, SATELLITE, LIGHT, DARK, EMERALD;

        static public List<MapStyle> supportedStyles() {
            List<MapStyle> styles = new ArrayList<MapStyle>();
            styles.add(STREETS);
            styles.add(SATELLITE);
            styles.add(LIGHT);
            styles.add(DARK);
            styles.add(EMERALD);
            return styles;
        }
        
        @Override
        public String toString() {
            switch (this) {
                case STREETS:
                    return "mapbox.streets";
                case SATELLITE:
                    return "mapbox.satellite";
                case LIGHT:
                    return "mapbox.light";
                case DARK:
                    return "mapbox.dark";
                case EMERALD:
                    return "mapbox.emerald";
            }

            return "";
        }
    }

    final private int MAX_FIREFOX_INSTANCE = WpThreadUtils.getMaxThreads();
    final private Semaphore availableFirefox = new Semaphore(MAX_FIREFOX_INSTANCE, true);

    final private LocationExtractor locationExtractor;
    final private Process xvfbCommand;
    final private Set<FirefoxDriver> drivers = new ConcurrentHashSet<FirefoxDriver>();
    private String scriptString;

    CompariFactReferenceMap(Env env) throws ConfigurationException {
        locationExtractor = new LocationExtractor(env);
        scriptString = "";

        // Load XVFB for the WebDriver
        int      DISPLAY_NUMBER  = 99;
        String   XVFB            = "Xvfb";
        String   XVFB_COMMAND    = XVFB + " :" + DISPLAY_NUMBER;

        int screenWidth = 1280;
        int screenHeight = 1024;
        int screenBitDepth = 8;

        for (int i = 0; i < MAX_FIREFOX_INSTANCE; i++) {
            XVFB_COMMAND += " -screen " + i + " " + screenWidth + "x" + screenHeight + "x" + screenBitDepth + " ";
        }

        try {
            xvfbCommand = Runtime.getRuntime().exec(XVFB_COMMAND);

            LOG.info("Warming up firefox");

            for (int i = 0; i < MAX_FIREFOX_INSTANCE; i++) {
                FirefoxBinary firefox = new FirefoxBinary();
                firefox.setEnvironmentProperty("DISPLAY", ":" + DISPLAY_NUMBER + "." + i);
                drivers.add(new FirefoxDriver(firefox, null));
            }

            LOG.info("Created " + MAX_FIREFOX_INSTANCE + " firefox instances for reference map rendering");

            loadCSSFile("index.css");
            loadJavaScriptFile("jquery.js");
            loadJavaScriptFile("d3.min.js");
            loadJavaScriptFile("d3.geo.tile.min.js");
            loadJavaScriptFile("topojson.js");
            loadJavaScriptFile("labeler.js");
            loadJavaScriptFile("annotations.js");
            loadJavaScriptFile("refMap.js");
        } catch (Exception  e) {
            throw new ConfigurationException("Unable to load required javascript engine");
        }
    }

    private FirefoxDriver getDriver() throws InterruptedException {
        availableFirefox.acquire();
        FirefoxDriver driver = drivers.iterator().next();
        drivers.remove(driver);
        return driver;
    }

    private void releaseDirver(FirefoxDriver driver) {
        drivers.add(driver);
        availableFirefox.release();
    }

    @Override
    protected void finalize() throws Throwable {
        super.finalize();
        close();
    }

    private void loadJavaScriptFile(String name) throws IOException {
        // Get the JS file from the resources folder
        ClassLoader classLoader = getClass().getClassLoader();
        File script = new File(classLoader.getResource("ReferenceMaps/" + name).getFile());
        scriptString += "<script type=\"text/javascript\" src='" + script.getAbsolutePath() + "'></script>\n";
    }

    private void loadCSSFile(String name) throws IOException {
        ClassLoader classLoader = getClass().getClassLoader();
        File script = new File(classLoader.getResource("ReferenceMaps/" + name).getFile());
        scriptString += "<link rel=\"stylesheet\" type=\"text/css\" href=\"" + script.getAbsolutePath() + "\">\n";
    }

    class ReferenceImage extends InternalImage {
        final public BufferedImage image;

        ReferenceImage(Language language, int sourceId, String name, String pageLocation, String imageLocation,
                      String caption, String method, boolean isPhotograph, int width, int height, double score, String title, BufferedImage image) {
            super(language, sourceId, name, pageLocation, imageLocation, caption, isPhotograph, width, height, method, score, title);
            this.image = image;
        }

        @Override
        public BufferedImage generateImage() throws IOException {
            return image;
        }

        @Override
        public BufferedImage generateImage(int width) throws IOException {
            int height = (int)((float)image.getHeight() * (float)width / (float)image.getWidth());
            Image temp = image.getScaledInstance(width, height, Image.SCALE_SMOOTH);
            BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_4BYTE_ABGR);

            Graphics2D g2d = result.createGraphics();
            g2d.drawImage(temp, 0, 0, null);
            g2d.dispose();

            return result;
        }
    }

    public ReferenceImage generateReferenceMap(MapStyle style, List<LocationExtractor.NamedGeometry> geometries) throws IOException {
        // Determine Map size and scale attributes
        int width = 1000;
        int height = 750;
        double zoom = width;
        double longCenter = -96.0;
        double latCenter = 38.3;

        JSONArray annotations = new JSONArray();

        if (geometries.size() > 0) {
            // Calculate a new center and extent
            longCenter = 0;
            latCenter = 0;
            Geometry bounds = null;

            try {
                for (int i = 0; i < geometries.size(); i++) {
                    Geometry g = geometries.get(i).geometry;
                    longCenter += g.getCentroid().getX();
                    latCenter += g.getCentroid().getY();

                    if (bounds == null) {
                        bounds = g.getBoundary();
                    } else {
                        bounds = bounds.union(g.getBoundary());
                    }

                    JSONObject coordinates = new JSONObject();
                    coordinates.put("lng", g.getCentroid().getX());
                    coordinates.put("lat", g.getCentroid().getY());

                    JSONObject json = new JSONObject();
                    json.put("location", coordinates.toString());
                    json.put("url", "");
                    json.put("text", "");
                    json.put("title", geometries.get(i).name);
                    annotations.put(json);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

            longCenter /= geometries.size();
            latCenter /= geometries.size();
            Envelope envelope = bounds.getEnvelopeInternal();
            if (envelope.getWidth() > 0.01 && envelope.getHeight() > 0.01) {
                zoom = Math.min(width * 62 / envelope.getWidth(), height * 31.0 / envelope.getHeight());
            }
        }

        String htmlString =
                "<!DOCTYPE html>\n" +
                "<html>\n" +
                    "<head>\n" +
                        "<meta charset=\"UTF-8\">\n" +
                        scriptString +
                    "</head>\n" +
                    "<body>\n" +
                        "<div id='visualization-container'>\n" +
                        "</div>\n" +
                        "<script>\n" +
                            "generateMap(" + annotations.toString() + ", '" + style.toString() + "', [" + longCenter + ", " + latCenter + "], " + zoom + ");\n" +
                        "</script>\n" +
                    "</body>\n" +
                "</html>\n";
        File tempHTMLFile = File.createTempFile("ref-map", ".html");
        BufferedWriter writer = new BufferedWriter(new FileWriter(tempHTMLFile));
        writer.write(htmlString);
        writer.close();

        File scrFile = null;
        try {

            FirefoxDriver driver = getDriver();
            driver.manage().window().setSize(new Dimension(width, height));
            driver.get("file://" + tempHTMLFile.getAbsolutePath());

            // Get the image
            scrFile = driver.getScreenshotAs(OutputType.FILE);
            releaseDirver(driver);
            driver = null;
        } catch (InterruptedException e) {
            throw new IOException("Unable to get web driver");
        }

        BufferedImage image = ImageIO.read(scrFile);

        // Get Metadata
        String title = "Map";
        String caption = "Reference Map";
        double score = 0.8 + Math.random() * 0.2;
        score = 1.0;

        // Note that the location will no longer be valid
        // String imageLocation = scrFile.getCanonicalPath();

        // Clean up files (we don't want our temp files hanging around too long)
        tempHTMLFile.delete();
        scrFile.delete();

        return new ReferenceImage(Language.EN, -1, "", "", null, caption, "ref-map", false, width, height, score, title, image);
    }

    public List<InternalImage> generateimages(final String text, String method) throws DaoException {
        final List<InternalImage> result = Collections.synchronizedList(new ArrayList<InternalImage>());
        System.out.println("Generating Reference map images");

        ParallelForEach.loop(locationExtractor.supportedExtractionTypes(), new Procedure<LocationExtractor.ExtractionType>() {
            @Override
            public void call(LocationExtractor.ExtractionType type) throws Exception {
                final List<LocationExtractor.NamedGeometry> locations = locationExtractor.extractLocations(text, type);

                if (locations.size() == 0) {
                    return;
                }

                String locationsString = "Extracted locations:\n";
                for (LocationExtractor.NamedGeometry loc : locations) {
                    locationsString += "\t" + loc.name + "\n";
                }
                LOG.info(locationsString);

                ParallelForEach.loop(MapStyle.supportedStyles(), new Procedure<MapStyle>() {
                    @Override
                    public void call(MapStyle style) throws Exception {
                        result.add(generateReferenceMap(style, locations));
                    }
                });
            }
        });

        System.out.println("Generated " + result.size() + " reference map images");

        return result;
    }

    public void close() {
        for (FirefoxDriver driver : drivers) {
            driver.close();
        }
        xvfbCommand.destroy();
    }
}

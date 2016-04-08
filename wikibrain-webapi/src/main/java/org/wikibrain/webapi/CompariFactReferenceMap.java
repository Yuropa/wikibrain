package org.wikibrain.webapi;

import com.vividsolutions.jts.geom.Envelope;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import org.apache.commons.io.IOUtils;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.support.ui.ExpectedCondition;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.wikibrain.conf.ConfigurationException;
import org.wikibrain.conf.Configurator;
import org.wikibrain.core.cmd.Env;
import org.wikibrain.core.dao.DaoException;
import org.wikibrain.core.lang.Language;
import org.wikibrain.core.model.LocalLink;
import org.wikibrain.spatial.dao.SpatialDataDao;
import org.wikibrain.sr.wikify.Wikifier;
import com.vividsolutions.jts.geom.Geometry;

import javax.imageio.ImageIO;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import java.awt.image.BufferedImage;
import java.io.*;
import java.sql.Ref;
import java.util.*;
import java.util.regex.Matcher;

import org.openqa.selenium.firefox.FirefoxBinary;
import org.openqa.selenium.firefox.FirefoxDriver;

/**
 * Created by Josh on 4/7/16.
 */
public class CompariFactReferenceMap implements CompariFactDataSource {
    private enum MapStyle {
        STREETS, SATELLITE, LIGHT, DARK, EMERALD;

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

    // final private SpatialDataDao spatialDataDao;
    final private Wikifier wikifier;
    final private Process xvfbCommand;
    final private FirefoxDriver driver;
    private String scriptString;

    CompariFactReferenceMap(Env env) throws ConfigurationException {
        Configurator conf = env.getConfigurator();
        Language lang = env.getDefaultLanguage();

        scriptString = "";

        // spatialDataDao = conf.get(SpatialDataDao.class);
        wikifier = conf.get(Wikifier.class, "websail", "language", lang.getLangCode());

        // Load XVFB for the WebDriver
        int      DISPLAY_NUMBER  = 99;
        String   XVFB            = "Xvfb";
        String   XVFB_COMMAND    = XVFB + " :" + DISPLAY_NUMBER;

        try {
            xvfbCommand = Runtime.getRuntime().exec(XVFB_COMMAND);
            FirefoxBinary firefox = new FirefoxBinary();
            firefox.setEnvironmentProperty("DISPLAY", ":" + DISPLAY_NUMBER);
            driver = new FirefoxDriver(firefox, null);

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

    private List<Geometry> locations(String text) throws DaoException {
        List<Geometry> result = new ArrayList<Geometry>();

        final Map<LocalLink, Double> values = new HashMap<LocalLink, Double>();
        for (LocalLink ll : wikifier.wikify(text)) {
            Double value = 1.0 - (double) ll.getLocation() / (double) text.length();
            if (values.containsKey(ll)) {
                value += values.get(ll);
            }

            values.put(ll, value);
        }

        List<LocalLink> links = new ArrayList<LocalLink>(values.keySet());
        links.sort(new Comparator<LocalLink>() {
            @Override
            public int compare(LocalLink o1, LocalLink o2) {
                return values.get(o2).compareTo(values.get(o1));
            }
        });

        /*for (LocalLink ll : links) {
            Geometry geometry = spatialDataDao.getGeometry(ll.getLocalId(), "state");

            if (geometry == null) {
                geometry = spatialDataDao.getGeometry(ll.getLocalId(), "country");
            }

            result.add(geometry);
        }*/

        return result;
    }

    class ReferenceImage extends InternalImage {
        final public BufferedImage image;

        ReferenceImage(Language language, int sourceId, String name, String pageLocation, String imageLocation,
                      String caption, String method, double score, String title, BufferedImage image) {
            super(language, sourceId, name, pageLocation, imageLocation, caption, method, score, title);
            this.image = image;
        }

        @Override
        public BufferedImage generateImage() throws IOException {
            return image;
        }
    }

    public ReferenceImage generateReferenceMap(MapStyle style, List<Geometry> geometries) throws IOException {
        // Determine Map size and scale attributes
        int width = 1000;
        int height = 750;
        double zoom = width;
        double longCenter = -96.0;
        double latCenter = 38.3;

        if (geometries.size() > 0) {
            // Calculate a new center and extent
            longCenter = 0;
            latCenter = 0;
            Geometry bounds = null;

            for (Geometry g : geometries) {
                longCenter += g.getCentroid().getX();
                latCenter += g.getCentroid().getY();

                if (bounds == null) {
                    bounds = g.getBoundary();
                } else  {
                    bounds = bounds.union(g.getBoundary());
                }
            }

            longCenter /= geometries.size();
            latCenter /= geometries.size();
            Envelope envelope = bounds.getEnvelopeInternal();
            zoom = Math.min(width / envelope.getWidth(), height / envelope.getHeight());
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
                            "generateMap([], '" + style.toString() + "', [" + longCenter + ", " + latCenter + "], " + zoom + ");\n" +
                        "</script>\n" +
                    "</body>\n" +
                "</html>\n";
        File tempHTMLFile = File.createTempFile("ref-map", ".html");
        BufferedWriter writer = new BufferedWriter(new FileWriter(tempHTMLFile));
        writer.write(htmlString);
        writer.close();

        driver.manage().window().setSize(new Dimension(width, height));
        driver.get("file://" + tempHTMLFile.getAbsolutePath());

        // Get the image
        File scrFile = driver.getScreenshotAs(OutputType.FILE);
        BufferedImage image = ImageIO.read(scrFile);

        // Get Metadata
        String title = "Map";
        String caption = "Reference Map";
        double score = 0.8 + Math.random() * 0.2;
        score = 1.0;
        // Image location is needed to ensure that the files can be differentiated later
        // Not that the location will no longer be valid
        String imageLocation = scrFile.getCanonicalPath();

        // Clean up files (we don't want our temp files hanging around too long)
        tempHTMLFile.delete();
        scrFile.delete();

        return new ReferenceImage(Language.EE, -1, "", "", imageLocation, caption, "ref-map", score, title, image);
    }

    public List<InternalImage> generateimages(String text, String method) throws DaoException {
        List<InternalImage> result = new ArrayList<InternalImage>();
        try {
            List<Geometry> locations = locations(text);
            int topNLocations[] = {1, 3, -1};
            for (int i : topNLocations) {
                if (i >= locations.size()) continue;

                List<Geometry> subsetLocations = locations;
                if (i > 0) {
                    subsetLocations = subsetLocations.subList(0, i);
                }

                result.add(generateReferenceMap(MapStyle.SATELLITE, subsetLocations));
                result.add(generateReferenceMap(MapStyle.STREETS, subsetLocations));
                result.add(generateReferenceMap(MapStyle.EMERALD, subsetLocations));
            }
        } catch (IOException e) {
            throw new DaoException("Unable to generate reference map");
        }

        return result;
    }

    public void close() {
        driver.quit();
        xvfbCommand.destroy();
    }
}

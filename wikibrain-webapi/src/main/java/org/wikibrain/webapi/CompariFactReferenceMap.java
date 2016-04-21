package org.wikibrain.webapi;

import com.vividsolutions.jts.geom.*;
import org.eclipse.jetty.util.ConcurrentHashSet;
import org.json.JSONObject;
import org.json.JSONArray;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.OutputType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wikibrain.conf.ConfigurationException;
import org.wikibrain.core.cmd.Env;
import org.wikibrain.core.dao.DaoException;
import org.wikibrain.core.dao.LocalPageDao;
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
import org.wikibrain.spatial.dao.SpatialDataDao;
import org.wikibrain.sr.SRMetric;
import org.wikibrain.utils.ParallelForEach;
import org.wikibrain.utils.Procedure;
import org.wikibrain.wikidata.WikidataDao;

/**
 * Created by Josh on 4/7/16.
 */
public class CompariFactReferenceMap implements CompariFactDataSource {
    private static final Logger LOG = LoggerFactory.getLogger(CompariFactReferenceMap.class);
    private enum MapStyle {
        RELIEF, SATELLITE, LABELS, BOUNDARIES, ROADS, LAND_USAGE, WATER;

        static public List<MapStyle> supportedStyles() {
            List<MapStyle> styles = new ArrayList<MapStyle>();
            styles.add(RELIEF);
            styles.add(SATELLITE);
            styles.add(LABELS);
            styles.add(BOUNDARIES);
            styles.add(ROADS);
            // styles.add(LAND_USAGE); // disable for now
            styles.add(WATER);
            return styles;
        }
        
        @Override
        public String toString() {
            switch (this) {
                case RELIEF:
                    return "relief";
                case SATELLITE:
                    return "terrain";
                case LABELS:
                    return "labels";
                case BOUNDARIES:
                    return "boundaries";
                case ROADS:
                    return "roads";
                case LAND_USAGE:
                    return "land-usage";
                case WATER:
                    return "water";
            }

            return "";
        }
    }

    final private double MIN_STYLE_SR = 0.5;
    final private boolean GENERATE_JSON = true;
    final private int MAX_FIREFOX_INSTANCE = 0; // = WpThreadUtils.getMaxThreads();
    final private Semaphore availableFirefox = new Semaphore(MAX_FIREFOX_INSTANCE, true);

    final private SRMetric srMetric;
    final private LocationExtractor locationExtractor;
    final private Process xvfbCommand;
    final private Set<FirefoxDriver> drivers = new ConcurrentHashSet<FirefoxDriver>();
    private String scriptString;

    CompariFactReferenceMap(Env env) throws ConfigurationException {
        locationExtractor = new LocationExtractor(env);
        srMetric = env.getConfigurator().get(SRMetric.class, "milnewitten", "lang", env.getDefaultLanguage().getLangCode());
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

            loadCSSFile("style.css");
            loadCSSFile("mapbox.css");
            loadJavaScriptFile("api.tiles.mapbox.min.js");
            loadJavaScriptFile("jquery.js");
            loadJavaScriptFile("d3.min.js");
            loadJavaScriptFile("topojson.js");
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

    public class MapConstructionData {
        public double southWestLat;
        public double southWestLng;
        public double northEastLat;
        public double northEastLng;
        final public List<String> annotations;
        final public List<Double> annotationsLat;
        final public List<Double> annotationsLng;
        final public List<MapStyle> style;

        MapConstructionData(double southWestLat, double southWestLng, double northEastLat, double northEastLng) {
            this.southWestLat = southWestLat;
            this.southWestLng = southWestLng;
            this.northEastLat = northEastLat;
            this.northEastLng = northEastLng;

            style = new ArrayList<MapStyle>();
            annotations = new ArrayList<String>();
            annotationsLat = new ArrayList<Double>();
            annotationsLng = new ArrayList<Double>();
        }

        void addStyle(MapStyle style) {
            this.style.add(style);
        }

        void addAnnotation(double lat, double lng, String title) {
            annotationsLat.add(lat);
            annotationsLng.add(lng);
            annotations.add(title);
        }

        String toJSON() {
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("sw-lat", southWestLat);
            jsonObject.put("sw-lng", southWestLng);
            jsonObject.put("ne-lat", northEastLat);
            jsonObject.put("ne-lng", northEastLng);

            JSONArray anns = new JSONArray();
            for (int i = 0; i < annotations.size(); i++) {
                JSONObject annotation = new JSONObject();
                annotation.put("lat", annotationsLat.get(i));
                annotation.put("lng", annotationsLng.get(i));
                annotation.put("title", annotations.get(i));
                anns.put(annotation);
            }

            jsonObject.put("annotations", anns);

            JSONArray styles = new JSONArray();
            for (MapStyle s : style) {
                styles.put(s.toString());
            }
            jsonObject.put("styles", styles);

            return jsonObject.toString();
        }
    }

    public class ReferenceImage extends InternalImage {
        final public BufferedImage image;
        final public boolean hasImage;
        final public String mapJSON;

        ReferenceImage(Language language, int sourceId, String name, String pageLocation, String imageLocation,
                      String caption, String method, boolean isPhotograph, int width, int height, double score, String title, BufferedImage image) {
            super(language, sourceId, name, pageLocation, imageLocation, caption, isPhotograph, width, height, method, score, title);
            this.image = image;
            hasImage = true;
            mapJSON = null;
        }

        ReferenceImage(Language language, int sourceId, String name, String pageLocation, String imageLocation,
                       String caption, String method, boolean isPhotograph, int width, int height, double score, String title,
                       MapConstructionData data) {
            super(language, sourceId, name, pageLocation, imageLocation, caption, isPhotograph, width, height, method, score, title);
            this.image = null;
            hasImage = false;

            mapJSON = data.toJSON();
        }

        @Override
        public BufferedImage generateImage() throws IOException {
            return image;
        }

        @Override
        public BufferedImage generateImage(int width) throws IOException {
            if (width < 0) {
                return image;
            }

            int height = (int)((float)image.getHeight() * (float)width / (float)image.getWidth());
            Image temp = image.getScaledInstance(width, height, Image.SCALE_SMOOTH);
            BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_4BYTE_ABGR);

            Graphics2D g2d = result.createGraphics();
            g2d.drawImage(temp, 0, 0, null);
            g2d.dispose();

            return result;
        }
    }

    public ReferenceImage generateReferenceMap(List<MapStyle> styles, List<LocationExtractor.NamedGeometry> geometries) throws IOException {
        // Determine Map size and scale attributes
        int width = 1000;
        int height = 750;

        JSONArray annotations = new JSONArray();
        MapConstructionData mapConstruction = new MapConstructionData(-85.0, 180.0, 85.0, -180.0);

        if (geometries.size() > 0) {
            // Calculate a new center and extent
            Geometry bounds = null;

            try {
                for (int i = 0; i < geometries.size(); i++) {
                    Geometry g = geometries.get(i).geometry;
                    Geometry boundary = locationExtractor.containingBounds(geometries.get(i));

                    if (bounds == null) {
                        bounds = boundary;
                    } else {
                        bounds = bounds.union(boundary);
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

                    mapConstruction.addAnnotation(g.getCentroid().getY(), g.getCentroid().getX(), geometries.get(i).name);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

            if (bounds != null && bounds.getEnvelopeInternal().getArea() > 0.01) {
                Envelope envelope = bounds.getEnvelopeInternal();
                mapConstruction.northEastLat = envelope.getMaxY();
                mapConstruction.northEastLng = envelope.getMaxX();
                mapConstruction.southWestLat = envelope.getMinY();
                mapConstruction.southWestLng = envelope.getMinX();
            } else {
                // Set to world bounds
                mapConstruction.northEastLat = 85;
                mapConstruction.northEastLng = 180;
                mapConstruction.southWestLat = -85;
                mapConstruction.southWestLng = -180;
            }
        }

        // Get Metadata
        String title = "Map";
        String caption = "Map of ";
        for (int i = 0; i < geometries.size(); i++) {
            if (i != 0) {
                caption += ", ";
            }
            if (i == geometries.size() - 1 && geometries.size() > 1) {
                caption += "and ";
            }

            caption += geometries.get(i).name;
        }
        double score = 1.0; // 0.8 + Math.random() * 0.2;

        if (GENERATE_JSON) {
            return new ReferenceImage(Language.EN, -1, "", "", null, caption, "ref-map", false, width, height, score, title, mapConstruction);
        } else {
            String bounds = "[[" + mapConstruction.southWestLat + "," + mapConstruction.southWestLng + "],[" +
                    mapConstruction.northEastLat + "," + mapConstruction.northEastLng + "]]";
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
                            "generateMap(" + annotations.toString() + ", '" + style.toString() + "', " + bounds + ");\n" +
                            "</script>\n" +
                            "</body>\n" +
                            "</html>\n";
            File tempHTMLFile = File.createTempFile("ref-map", ".html");
            BufferedWriter writer = new BufferedWriter(new FileWriter(tempHTMLFile));
            writer.write(htmlString);
            writer.close();

            File scrFile;
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

            // Note that the location will no longer be valid
            // String imageLocation = scrFile.getCanonicalPath();

            // Clean up files (we don't want our temp files hanging around too long)
            tempHTMLFile.delete();
            scrFile.delete();

            return new ReferenceImage(Language.EN, -1, "", "", null, caption, "ref-map", false, width, height, score, title, image);
        }
    }

    public List<InternalImage> generateimages(String text, String method) throws DaoException {
        final List<InternalImage> result = Collections.synchronizedList(new ArrayList<InternalImage>());
        System.out.println("Generating Reference map images");

        // Generate all geometric combinations and remove duplicates
        Set<Set<LocationExtractor.NamedGeometry>> extractedGeometries = new HashSet<Set<LocationExtractor.NamedGeometry>>();
        for (LocationExtractor.ExtractionType type : locationExtractor.supportedExtractionTypes()) {
            List<LocationExtractor.NamedGeometry> locations = locationExtractor.extractLocations(text, type);

            if (locations.size() == 0) {
                continue;
            }

            extractedGeometries.add(new HashSet<LocationExtractor.NamedGeometry>(locations));
        }

        // Print the extracted groups
        for (Set<LocationExtractor.NamedGeometry> set : extractedGeometries) {
            String locationsString = "Extracted locations:\n";
            for (LocationExtractor.NamedGeometry loc : set) {
                locationsString += "\t" + loc.name + "\n";
            }
            LOG.info(locationsString);
        }

        // Use SR to find the styles to use
        final List<MapStyle> styles = new ArrayList<MapStyle>();
        for (MapStyle style : MapStyle.supportedStyles()) {
            if (srMetric.similarity(style.toString(), text, false).getScore() > MIN_STYLE_SR) {
                styles.add(style);
            }
        }

        // Generate the Reference Maps
        ParallelForEach.loop(extractedGeometries, new Procedure<Set<LocationExtractor.NamedGeometry>>() {
            @Override
            public void call(Set<LocationExtractor.NamedGeometry> locationSet) throws Exception {
                List<LocationExtractor.NamedGeometry> locations = new ArrayList<LocationExtractor.NamedGeometry>(locationSet);
                result.add(generateReferenceMap(styles, locations));
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

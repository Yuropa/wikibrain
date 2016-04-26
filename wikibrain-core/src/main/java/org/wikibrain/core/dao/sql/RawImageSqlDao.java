package org.wikibrain.core.dao.sql;


import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.typesafe.config.Config;
import de.tudarmstadt.ukp.wikipedia.parser.Link;
import de.tudarmstadt.ukp.wikipedia.parser.ParsedPage;
import de.tudarmstadt.ukp.wikipedia.parser.mediawiki.MediaWikiParser;
import de.tudarmstadt.ukp.wikipedia.parser.mediawiki.MediaWikiParserFactory;
import org.apache.commons.io.IOUtils;
import org.jooq.tools.json.JSONObject;
import org.jooq.tools.json.JSONParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wikibrain.conf.Configuration;
import org.wikibrain.conf.ConfigurationException;
import org.wikibrain.conf.Configurator;
import org.wikibrain.core.dao.*;
import org.wikibrain.core.lang.Language;
import org.wikibrain.core.lang.LanguageSet;
import org.wikibrain.core.model.*;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;

/**
 * Created by Josh on 3/30/16.
 * TODO: Store this into the database!
 */
public class RawImageSqlDao implements RawImageDao {
    private static final Logger LOG = LoggerFactory.getLogger(RawLinkSqlDao.class);

    private final LocalPageDao lpDao;
    private final RawLinkDao rawLinkDao;
    private MediaWikiParser parser;

    public RawImageSqlDao(RawLinkDao rawLinkDao, LocalPageDao lpDao, MediaWikiParser parser) throws DaoException {
        this.rawLinkDao = rawLinkDao;
        this.lpDao = lpDao;
        this.parser = parser;
    }

    private boolean targetToImage(String s) {
        return s.startsWith("Image:") || s.startsWith("File:") || s.startsWith("IMAGE:") || s.startsWith("FILE:");
    }

    private String trimTargetName(String s) {
        if (s.startsWith("Image:") || s.startsWith("IMAGE:")) {
            s = s.substring("Image:".length());
        } else if (s.startsWith("File:") || s.startsWith("FILE:")) {
            s = s.substring("File:".length());
        }

        return s;
    }

    private RawImage parseImage(JsonObject page) {
        String name = page.get("title").getAsString();

        JsonObject properties;
        if (page.has("imageinfo")) {
            properties = page.getAsJsonArray("imageinfo").get(0).getAsJsonObject();
        } else  {
            properties = page;
        }

        String image = properties.get("url").getAsString();
        int width = properties.get("width").getAsInt();
        int height = properties.get("height").getAsInt();

        boolean hasMake = false;
        boolean hasModel = false;
        boolean categoriesIndicatePhoto = false;
        boolean isCoatOfArms = false;
        boolean isRoadSign = false;
        boolean hasLargeAmountOfMetadata = false;

        if (properties.has("commonmetadata")) {
            JsonArray metadata = properties.getAsJsonArray("commonmetadata");
            for (int i = 0; i < metadata.size(); i++) {
                JsonObject data = metadata.get(i).getAsJsonObject();

                if (data.get("name").getAsString().equals("Make")) {
                    hasMake = true;
                } else if (data.get("name").getAsString().equals("Model")) {
                    hasModel = true;
                }
            }
            hasLargeAmountOfMetadata = metadata.size() >= 5;
        }

        if (page.has("categories")) {
            JsonArray categories = page.getAsJsonArray("categories");
            for (int i = 0; i < categories.size(); i++) {
                JsonObject category = categories.get(i).getAsJsonObject();
                String title = category.get("title").getAsString().toLowerCase();

                if (title.contains("image") || (title.contains("picture") && !title.contains("featured"))
                        || title.contains("photo") || title.contains("portrait")) {
                    categoriesIndicatePhoto = true;
                    break;
                } else if (title.contains("coat") && title.contains("arm")) {
                    isCoatOfArms = true;
                    break;
                } else if (title.contains("road") && title.contains("sign")) {
                    isRoadSign = true;
                    break;
                }
            }
        }

        boolean isPhotograph = hasMake || hasModel || hasLargeAmountOfMetadata || categoriesIndicatePhoto
                || isCoatOfArms || isRoadSign;

        RawImage i = new RawImage(name, image, "", isPhotograph, width, height);
        return i;
    }

    private class AllImageIterator extends CommonsNetworkIterator<RawImage> {
        AllImageIterator(String start, String end) throws Exception {
            super(start, end);
            performDownload();
        }

        @Override
        String buildSearchURL(String start, String end) {
            String format = "&format=json";
            String limit = "&ailimit=" + 20;
            String from = start.length() > 0 ? "&aifrom=" + start : "";
            String to = end.length() > 0 ? "&aito=" + end : "";

            return "https://en.wikipedia.org/w/api.php?action=query&list=allimages"
                        + format
                        + limit
                        + from
                        + to
                        + "&aiprop=timestamp|parsedcomment|url|dimensions|mediatype|commonmetadata|bitdepth"
                        + "|extmetadata|canonicaltitle|commonmetadata";
        }

        @Override
        String continueSearchKey() {
            return "aicontinue";
        }

        @Override
        JsonArray getQueryResult(JsonObject json) {
            return json.getAsJsonArray("allimages");
        }

        @Override
        RawImage buildObject(JsonObject json) {
            return parseImage(json);
        }
    }
    public RawImage getImage(String title) throws DaoException {
        Iterator<RawImage> images = getImages(title, title);
        if (images.hasNext()) {
            return images.next();
        }

        return null;
    }

    public Iterator<RawImage> getImages(String startingTitle, String endingTitle) throws DaoException {
        try {
            return new AllImageIterator(startingTitle, endingTitle);
        } catch (Exception e) {
            throw new DaoException(e.getLocalizedMessage());
        }
    }


    private class ImagePageIterator extends CommonsNetworkIterator<LocalPage> {
        private String page;
        private LocalPageDao dao;

        ImagePageIterator(String page, LocalPageDao dao) throws Exception {
            super("", "");
            this.page = page;
            this.dao = dao;

            performDownload();
        }

        @Override
        String buildSearchURL(String start, String end) {
            String format = "&format=json";
            String from = start.length() > 0 ? "&fucontinue=" + start : "";

            return "https://en.wikipedia.org/w/api.php?action=query&titles="
                    + page
                    + format
                    + from
                    + "&funamespace=0"
                    + "&prop=fileusage";
        }

        @Override
        String continueSearchKey() {
            return "fucontinue";
        }

        @Override
        JsonArray getQueryResult(JsonObject json) {
            JsonObject pages = json.getAsJsonObject("pages");
            for (Map.Entry<String, JsonElement> entry : pages.entrySet()) {
                return entry.getValue().getAsJsonObject().getAsJsonArray("fileusage");
            }

            return null;
        }

        @Override
        LocalPage buildObject(JsonObject json) {
            int pageId = json.get("pageid").getAsInt();
            int ns = json.get("ns").getAsInt();
            String title = json.get("title").getAsString();

            try {
                return dao.getByTitle(Language.SIMPLE, NameSpace.getNameSpaceByValue(ns), title);
            } catch (DaoException e) {
                LOG.info(e.getLocalizedMessage());
            }

            return null;
        }

    }
    public Iterator<LocalPage> pagesWithImage(RawImage image) throws DaoException {
        try {
            return new ImagePageIterator(image.getName().replace(" ", "_"), lpDao);
        } catch (Exception e) {
            throw new DaoException(e.getLocalizedMessage());
        }
    }


    public Iterable<RawImage> getImages(Language language, int localId) throws DaoException {
        List<Integer> ids = new ArrayList<Integer>();
        ids.add(localId);
        Map<Integer, List<RawImage>> allImages = getImages(language, ids);
        if (allImages.containsKey(localId)) {
            return allImages.get(localId);
        }

        return new ArrayList<RawImage>();
    }

    public Map<Integer, List<RawImage>> getImages(Language language, List<Integer> localIds) throws DaoException {
        Map<Integer, List<RawImage>> result = new HashMap<Integer, List<RawImage>>();

        String titles = "";
        Map<String, RawLink> titleLinkMap = new HashMap<String, RawLink>();
        for (Integer localId : localIds) {
            for (RawLink l : rawLinkDao.getLinks(language, localId)) {
                if (l.getType() == RawLink.RawLinkType.IMAGE || (l.getType() == RawLink.RawLinkType.UNKNOWN && targetToImage(l.getTarget()))) {
                    // Found an image
                    String name = l.getTarget();

                    if (targetToImage(name)) {
                        name = "File:" + trimTargetName(name);

                        if (titles.length() > 0) {
                            titles += "|";
                        }
                        titles += name;
                        titleLinkMap.put(name.toLowerCase().replace("_", " "), l);
                    }
                }
            }
        }

        // If we didn't find any images, bail early
        if (titles.length() == 0) {
            return result;
        }

        int response = 0;
        InputStream in = null;
        try {
            String download = "https://commons.wikimedia.org/w/api.php?action=query&titles=" + titles + "&prop=imageinfo|categories&format=json&iiprop=commonmetadata|url|timestamp|user|size";

            URL url = new URL(download);
            HttpURLConnection connection = (HttpURLConnection)url.openConnection();
            connection.setRequestMethod("GET");
            response = connection.getResponseCode();
            in = new BufferedInputStream(connection.getInputStream());
            String jsonString = IOUtils.toString(in);
            JsonObject json = new JsonParser().parse(jsonString).getAsJsonObject();
            JsonObject pages = json.getAsJsonObject("query").getAsJsonObject("pages");

            for (Map.Entry<String, JsonElement> entry : pages.entrySet()) {
                try {
                    JsonObject page = entry.getValue().getAsJsonObject();

                    RawImage i = parseImage(page);

                    String name = i.getName().toLowerCase();
                    RawLink l = titleLinkMap.get(name);
                    if (l == null) {
                        String debugString = "Unable to find link for " + name + "\n";

                        for (String title : titleLinkMap.keySet()) {
                            debugString += "\t" + title + "\n";
                        }

                        debugString += "\n\n";
                        System.out.println(debugString);
                        continue;
                    }

                    // Get caption
                    String context = l.getContext();
                    String captionText = generateCaptionFromWikiText(context);
                    i.caption = captionText;

                    int localId = l.getSourceId();

                    if (!result.containsKey(localId)) {
                        result.put(localId, new ArrayList<RawImage>());
                    }

                    result.get(localId).add(i);
                } catch (Exception e) {
                    System.out.println("\n\n\nError downloading files: " + download);
                    LOG.debug(e.getLocalizedMessage());
                    e.printStackTrace();
                }
            }
        } catch (MalformedURLException e) {
            LOG.debug(e.getLocalizedMessage());
        } catch (IOException e) {
            LOG.debug(e.getLocalizedMessage());
            if (response == 414) {
                // The URL was too long, divide and conquer
                int length = localIds.size() / 2;
                if (length >= 1) {
                    result.putAll(getImages(language, localIds.subList(0, length)));
                    result.putAll(getImages(language, localIds.subList(length, localIds.size())));
                }
            }
        } finally {
            IOUtils.closeQuietly(in);
        }

        return result;
    }



    private String generateCaptionFromWikiText(String context) {
        String[] components = context.split("\\|");

        String captionText = "";

        // Try to parse the image WikiText
        // https://en.wikipedia.org/wiki/Wikipedia:Extended_image_syntax
        for (int i = 0; i < components.length; i++) {
            String s = components[i];
            s = s.trim();

            if (i == 0) {
                // This is the file name
                if (s.startsWith("File:")) {
                    int fileExtension = context.lastIndexOf(".");
                    s = s.substring("File:".length(), fileExtension);
                }

                captionText = s;
            } else if (s.startsWith("alt=")) {
                s = s.substring("alt=".length());
                captionText = s;
            } else if (s.equals("thumb") || s.equals("thumbnail") || s.equals("frame") || s.equals("framed")
                    || s.equals("frameless") || s.startsWith("thumb=") || s.startsWith("thumbnail=")) {
                // type attribute
                continue;
            } else if (s.equals("border")) {
                // border attribute
                continue;
            } else if (s.equals("right") || s.equals("left") || s.equals("center") || s.equals("none")) {
                // location attriubte
                continue;
            } else if (s.startsWith("link=")) {
                // link attribute
                continue;
            } else if (s.equals("upright") || s.startsWith("upright=") || s.endsWith("px")) {
                // size attribute
                continue;
            } else if (i == components.length - 1) {
                // This is where the caption would be located
                captionText = s;
            }
        }

        // Remove any remaining templates from the caption Text
        int index;
        String templateBeginning = "TEMPLATE";

        while ((index = captionText.indexOf(templateBeginning)) >= 0) {
            int start = index, end = -1;
            index += templateBeginning.length();

            int openIndex = captionText.indexOf("[", index);
            if (openIndex < 0)
                break;

            int brackets = 1;
            for (int i = openIndex + 1; i < captionText.length(); i++) {
                if (captionText.charAt(i) == '[') {
                    brackets++;
                } else if (captionText.charAt(i) == ']') {
                    brackets--;
                }

                if (brackets == 0) {
                    end = i + 1;
                    break;
                }
            }

            if (end > 0) {
                captionText = captionText.substring(0, start) + captionText.substring(end);
            }
        }
        return captionText;
    }

    // TODO: Implement these to interact with the SQL database
    public void clear() throws DaoException {}

    public void beginLoad() throws DaoException {

    }

    public void save(RawImage item) throws DaoException {

    }

    public void endLoad() throws DaoException {

    }

    public Iterable<RawImage> get(DaoFilter daoFilter) throws DaoException {
        return new ArrayList<RawImage>();
    }

    public int getCount(DaoFilter daoFilter) throws DaoException {
        return 0;
    }

    public LanguageSet getLoadedLanguages() throws DaoException {
        return rawLinkDao.getLoadedLanguages();
    }

    public static class Provider extends org.wikibrain.conf.Provider<RawImageDao> {
        public Provider(Configurator configurator, Configuration config) throws ConfigurationException {
            super(configurator, config);
        }

        @Override
        public Class<RawImageDao> getType() {
            return RawImageDao.class;
        }

        @Override
        public String getPath() {
            return "dao.rawImage";
        }

        @Override
        public RawImageDao get(String name, Config config, Map<String, String> runtimeParams) throws ConfigurationException {
            if (!config.getString("type").equals("sql")) {
                return null;
            }
            try {MediaWikiParserFactory pf = new MediaWikiParserFactory();
                pf.setCalculateSrcSpans(true);
                // pf.setCategoryIdentifers(langInfo.getCategoryNames());

                MediaWikiParser parser = pf.createParser();
                RawLinkDao rawLinkDao = getConfigurator().get(RawLinkDao.class);
                LocalPageDao localPageDao = getConfigurator().get(LocalPageDao.class);
                return new RawImageSqlDao(rawLinkDao, localPageDao, parser);
            } catch (DaoException e) {
                throw new ConfigurationException(e);
            }
        }
    }
}

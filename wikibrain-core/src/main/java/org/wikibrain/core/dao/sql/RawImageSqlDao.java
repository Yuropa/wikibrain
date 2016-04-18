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
import org.wikibrain.core.model.RawImage;
import org.wikibrain.core.model.RawLink;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Created by Josh on 3/30/16.
 * TODO: Store this into the database!
 */
public class RawImageSqlDao implements RawImageDao {
    private static final Logger LOG = LoggerFactory.getLogger(RawLinkSqlDao.class);

    private final RawLinkDao rawLinkDao;
    private MediaWikiParser parser;

    public RawImageSqlDao(RawLinkDao rawLinkDao, MediaWikiParser parser) throws DaoException {
        this.rawLinkDao = rawLinkDao;
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

    public Iterable<RawImage> getImages(Language language, int localId) throws DaoException {
        ArrayList<RawImage> result = new ArrayList<RawImage>();

        String titles = "";
        Map<String, RawLink> titleLinkMap = new HashMap<String, RawLink>();
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
                    titleLinkMap.put(name, l);
                }
            }
        }

        // If we didn't find any images, bail early
        if (titles.length() == 0) {
            return result;
        }

        InputStream in = null;
        try {
            String download = "https://commons.wikimedia.org/w/api.php?action=query&titles=" + titles + "&prop=imageinfo&format=json&iiprop=commonmetadata|url|timestamp|user|size";

            in = new URL(download).openStream();
            String jsonString = IOUtils.toString(in);
            JsonObject json = new JsonParser().parse(jsonString).getAsJsonObject();
            JsonObject pages = json.getAsJsonObject("query").getAsJsonObject("pages");

            for (Map.Entry<String, JsonElement> entry : pages.entrySet()) {
                JsonObject page = entry.getValue().getAsJsonObject();
                String name = page.get("title").getAsString().replace(" ", "_");
                RawLink l = titleLinkMap.get(name);

                String pageLocation = "https://commons.wikimedia.org/wiki/" + name;

                JsonObject properties = page.getAsJsonArray("imageinfo").get(0).getAsJsonObject();
                String image = properties.get("url").getAsString();
                int width = properties.get("width").getAsInt();
                int height = properties.get("height").getAsInt();

                JsonArray metadata = properties.getAsJsonArray("commonmetadata");
                boolean hasMake = false;
                boolean hasModel = false;
                for (int i = 0; i < metadata.size(); i++) {
                    JsonObject data = metadata.get(i).getAsJsonObject();

                    if (data.get("name").getAsString().equals("Make")) {
                        hasMake = true;
                    } else if (data.get("name").getAsString().equals("Model")) {
                        hasModel = true;
                    }
                }

                boolean isPhotograph = hasMake & hasModel;

                // Get caption
                String context = l.getContext();
                String captionText = generateCaptionFromWikiText(context);

                RawImage i = new RawImage(l.getLanguage(), l.getSourceId(), name, pageLocation, image, captionText, isPhotograph, width, height);
                result.add(i);
            }
        } catch (Exception e) {
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
                return new RawImageSqlDao(rawLinkDao, parser);
            } catch (DaoException e) {
                throw new ConfigurationException(e);
            }
        }
    }
}

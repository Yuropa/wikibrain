package org.wikibrain.core.dao.sql;


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
import org.wikibrain.core.model.RawPage;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Map;

/**
 * Created by Josh on 3/30/16.
 * TODO: Store this into the database!
 */
public class RawImageSqlDao implements RawImageDao {
    private static final Logger LOG = LoggerFactory.getLogger(RawLinkSqlDao.class);

    private final RawLinkDao rawLinkDao;

    public RawImageSqlDao(RawLinkDao rawLinkDao) throws DaoException {
        this.rawLinkDao = rawLinkDao;
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

        for (RawLink l : rawLinkDao.getLinks(language, localId)) {
            if (l.getType() == RawLink.RawLinkType.IMAGE || (l.getType() == RawLink.RawLinkType.UNKNOWN && targetToImage(l.getTarget()))) {
                // Found an image
                String name = l.getTarget();
                String page = name;
                String image = "";
                if (targetToImage(name)) {
                    // Remove the prefix
                    name = trimTargetName(name);
                    page = "https://commons.wikimedia.org/wiki/File:" + name;

                    String download = "https://commons.wikimedia.org/w/api.php?action=query&titles=File:" + name + "&prop=imageinfo&format=json&iiprop=url";

                    InputStream in = null;
                    try {
                        in = new URL(download).openStream();
                        JsonObject json = new JsonParser().parse(IOUtils.toString(in)).getAsJsonObject();
                        JsonObject pages = json.getAsJsonObject("query").getAsJsonObject("pages");
                        String pageKey = pages.entrySet().iterator().next().getKey();
                        image = pages.getAsJsonObject(pageKey).getAsJsonArray("imageinfo").get(0).getAsJsonObject().get("url").getAsString();
                    } catch (Exception e) {
                    } finally {
                        if (in != null)
                            IOUtils.closeQuietly(in);
                    }
                }

                RawImage i = new RawImage(l.getLanguage(), l.getSourceId(), name, page, image);
                result.add(i);
            }
        }

        return result;
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
            try {
                RawLinkDao rawLinkDao = getConfigurator().get(RawLinkDao.class);
                return new RawImageSqlDao(rawLinkDao);
            } catch (DaoException e) {
                throw new ConfigurationException(e);
            }
        }
    }
}

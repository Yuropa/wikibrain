package org.wikibrain.core.dao.sql;

import com.typesafe.config.Config;
import de.tudarmstadt.ukp.wikipedia.parser.Link;
import de.tudarmstadt.ukp.wikipedia.parser.ParsedPage;
import de.tudarmstadt.ukp.wikipedia.parser.mediawiki.MediaWikiParser;
import de.tudarmstadt.ukp.wikipedia.parser.mediawiki.MediaWikiParserFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wikibrain.conf.Configuration;
import org.wikibrain.conf.ConfigurationException;
import org.wikibrain.conf.Configurator;
import org.wikibrain.core.dao.DaoException;
import org.wikibrain.core.dao.DaoFilter;
import org.wikibrain.core.dao.RawLinkDao;
import org.wikibrain.core.dao.RawPageDao;
import org.wikibrain.core.lang.Language;
import org.wikibrain.core.lang.LanguageInfo;
import org.wikibrain.core.lang.LanguageSet;
import org.wikibrain.core.model.RawLink;
import org.wikibrain.core.model.RawPage;

import java.util.ArrayList;
import java.util.Map;

/**
 * Created by Josh on 3/30/16.
 * TODO: Store this into the database!
 */
public class RawLinkSqlDao implements RawLinkDao {
    private static final Logger LOG = LoggerFactory.getLogger(RawLinkSqlDao.class);
    private RawPageDao rawPageDao;
    private MediaWikiParser parser;

    public RawLinkSqlDao(RawPageDao rawPageDao, MediaWikiParser parser) throws DaoException{
        this.rawPageDao = rawPageDao;
        this.parser = parser;
    }

    public Iterable<RawLink> getLinks(Language language, int localId) throws DaoException {
        RawPage page = rawPageDao.getById(language, localId);
        ArrayList<RawLink> result = new ArrayList<RawLink>();

        try {
            ParsedPage pp = parser.parse(page.getBody());

            if (pp == null) {
                LOG.debug("invalid page: " + page.getBody());
            }

            for (Link l : pp.getLinks()) {
                RawLink.RawLinkType type = RawLink.RawLinkType.UNKNOWN;
                switch (l.getType()) {
                    case VIDEO:
                        type = RawLink.RawLinkType.VIDEO;
                        break;
                    case IMAGE:
                        type = RawLink.RawLinkType.IMAGE;
                        break;
                    case AUDIO:
                        type = RawLink.RawLinkType.AUDIO;
                        break;
                    case EXTERNAL:
                        type = RawLink.RawLinkType.EXTERNAL;
                        break;
                    case INTERNAL:
                        type = RawLink.RawLinkType.INTERNAL;
                        break;
                    case UNKNOWN:
                        type = RawLink.RawLinkType.UNKNOWN;
                        break;
                }

                RawLink rl = new RawLink(page.getLanguage(), localId, l.getText(), l.getPos().getStart(), l.getTarget(), type);
                result.add(rl);
            }

        } catch (Exception e) {
            // Should probably do something with the exception
        }

        return result;
    }

    // TODO: Implement these to interact with the SQL database
    public void clear() throws DaoException {}

    public void beginLoad() throws DaoException {

    }

    public void save(RawLink item) throws DaoException {

    }

    public void endLoad() throws DaoException {

    }

    public Iterable<RawLink> get(DaoFilter daoFilter) throws DaoException {
        return new ArrayList<RawLink>();
    }

    public int getCount(DaoFilter daoFilter) throws DaoException {
        return 0;
    }

    public LanguageSet getLoadedLanguages() throws DaoException {
        return rawPageDao.getLoadedLanguages();
    }

    public static class Provider extends org.wikibrain.conf.Provider<RawLinkDao> {
        public Provider(Configurator configurator, Configuration config) throws ConfigurationException {
            super(configurator, config);
        }

        @Override
        public Class<RawLinkDao> getType() {
            return RawLinkDao.class;
        }

        @Override
        public String getPath() {
            return "dao.rawLink";
        }

        @Override
        public RawLinkDao get(String name, Config config, Map<String, String> runtimeParams) throws ConfigurationException {
            if (!config.getString("type").equals("sql")) {
                return null;
            }
            try {
                // LanguageInfo langInfo = LanguageInfo.getByLanguage();

                MediaWikiParserFactory pf = new MediaWikiParserFactory();
                pf.setCalculateSrcSpans(true);
                // pf.setCategoryIdentifers(langInfo.getCategoryNames());

                MediaWikiParser parser = pf.createParser();
                return new RawLinkSqlDao(
                        getConfigurator().get(RawPageDao.class),
                        parser
                );
            } catch (DaoException e) {
                throw new ConfigurationException(e);
            }
        }
    }
}

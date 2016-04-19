package org.wikibrain.webapi;

import com.vividsolutions.jts.geom.Geometry;
import org.wikibrain.conf.ConfigurationException;
import org.wikibrain.conf.Configurator;
import org.wikibrain.core.cmd.Env;
import org.wikibrain.core.dao.DaoException;
import org.wikibrain.core.dao.LocalPageDao;
import org.wikibrain.core.lang.Language;
import org.wikibrain.core.model.LocalLink;
import org.wikibrain.core.model.LocalPage;
import org.wikibrain.spatial.constants.Layers;
import org.wikibrain.spatial.dao.SpatialDataDao;
import org.wikibrain.sr.wikify.Wikifier;

import java.util.*;

/**
 * Created by Josh on 4/14/16.
 */
public class LocationExtractor {
    public class NamedGeometry {
        public Geometry geometry;
        public String name;

        public NamedGeometry(Geometry geometry, String name) {
            this.geometry = geometry;
            this.name = name;
        }

        @Override
        public int hashCode() {
            return name.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof NamedGeometry) {
                NamedGeometry ng = (NamedGeometry)obj;
                return ng.name.equals(name);
            }

            return super.equals(obj);
        }
    }

    private class MapComparator<T, U extends Comparable<U>> implements Comparator<T> {
        public Map<T, U> values;

        @Override
        public int compare(T o1, T o2) {
            return values.get(o1).compareTo(values.get(o2));
        }
    }

    public enum ExtractionType {
        ALL,
        MOST_POPULAR,
        TOP_THREE,
        FIRST_SENTENCE,
        FIRST_PARAGRAPH
    }

    final private SpatialDataDao spatialDataDao;
    final private Wikifier wikifier;
    final private LocalPageDao lpDao;

    LocationExtractor(Env env) throws ConfigurationException {
        Configurator conf = env.getConfigurator();
        Language lang = env.getDefaultLanguage();

        if (lang.equals(Language.EN)) {
            spatialDataDao = conf.get(SpatialDataDao.class);
        } else {
            spatialDataDao = null;
        }
        wikifier = conf.get(Wikifier.class, "websail", "language", lang.getLangCode());
        lpDao = conf.get(LocalPageDao.class);
    }


    private Geometry getGeometry(SpatialDataDao spatialDao, String title, Language lang) throws DaoException {
        Geometry result = spatialDao.getGeometry(title, lang, Layers.STATE);
        if (result != null) {
            return result;
        }

        result = spatialDao.getGeometry(title, lang, Layers.COUNTRY);
        if (result != null){
            return result;
        }

        result = spatialDao.getGeometry(title, lang, Layers.WIKIDATA);
        return result;
    }

    private List<NamedGeometry> rankedLinks(String text) throws DaoException {
        Map<LocalLink, Double> values = new HashMap<LocalLink, Double>();
        for (LocalLink ll : wikifier.wikify(text)) {
            Double value = 1.0 - (double) ll.getLocation() / (double) text.length();
            if (values.containsKey(ll)) {
                value += values.get(ll);
            }

            values.put(ll, value);
        }

        List<LocalLink> links = new ArrayList<LocalLink>(values.keySet());
        MapComparator comparator = new MapComparator<LocalLink, Double>();
        comparator.values = values;
        Collections.sort(links, Collections.reverseOrder(comparator));

        Map<NamedGeometry, Integer> geometryCount = new HashMap<NamedGeometry, Integer>();
        if (spatialDataDao != null) {
            for (LocalLink ll : links) {
                LocalPage lp = lpDao.getById(ll.getLanguage(), ll.getLocalId());

                Geometry geometry = getGeometry(spatialDataDao, lp.getTitle().getCanonicalTitle(), lp.getLanguage());

                if (geometry == null) {
                    continue;
                }

                NamedGeometry namedGeometry = new NamedGeometry(geometry, lp.getTitle().getCanonicalTitle());
                int count = 1;

                if (geometryCount.containsKey(namedGeometry)) {
                    count += geometryCount.get(namedGeometry);
                }

                geometryCount.put(namedGeometry, count);
            }
        }
        List<NamedGeometry> result = new ArrayList<NamedGeometry>(geometryCount.keySet());
        MapComparator geometryComparator = new MapComparator<NamedGeometry, Integer>();
        geometryComparator.values = geometryCount;
        Collections.sort(result, Collections.reverseOrder(geometryComparator));

        return result;
    }

    private List<NamedGeometry> allRankedLinks(String text) throws DaoException { return rankedLinks(text); }
    private List<NamedGeometry> topNRankedLinks(String text, int n) throws DaoException {
        List<NamedGeometry> result = rankedLinks(text);
        if (result.size() >= n) {
            return result.subList(0, n);
        } else {
            return result.subList(0, result.size());
        }
    }
    private List<NamedGeometry> mostPopularRankedLinks(String text) throws DaoException { return topNRankedLinks(text, 1); }
    private List<NamedGeometry> topThreeRankedLinks(String text) throws DaoException { return topNRankedLinks(text, 3); }
    private List<NamedGeometry> untilCharacterRankedLinks(String text, String stopSequence) throws DaoException {
        int index = text.indexOf(stopSequence);
        if (index > 0) {
            text = text.substring(0, index);
        }
        return allRankedLinks(text);
    }
    private List<NamedGeometry> firstSentenceRankedLinks(String text) throws DaoException { return untilCharacterRankedLinks(text, "."); }
    private List<NamedGeometry> firstParagraphRankedLinks(String text) throws DaoException { return untilCharacterRankedLinks(text, "\n"); }

    public List<NamedGeometry> extractLocations(String text, ExtractionType type) throws DaoException {
        switch (type) {
            case ALL:
                return allRankedLinks(text);
            case MOST_POPULAR:
                return mostPopularRankedLinks(text);
            case TOP_THREE:
                return topThreeRankedLinks(text);
            case FIRST_SENTENCE:
                return firstSentenceRankedLinks(text);
            case FIRST_PARAGRAPH:
                return firstParagraphRankedLinks(text);
        }

        return Collections.emptyList();
    }

    public List<ExtractionType> supportedExtractionTypes() {
        List<ExtractionType> result = new ArrayList<ExtractionType>();
        result.add(ExtractionType.ALL);
        result.add(ExtractionType.MOST_POPULAR);
        result.add(ExtractionType.TOP_THREE);
        result.add(ExtractionType.FIRST_SENTENCE);
        result.add(ExtractionType.FIRST_PARAGRAPH);
        return result;
    }
}

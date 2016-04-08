package org.wikibrain.webapi;

import cern.jet.random.StudentT;
import org.wikibrain.conf.Configuration;
import org.wikibrain.conf.ConfigurationException;
import org.wikibrain.conf.Configurator;
import org.wikibrain.core.cmd.Env;
import org.wikibrain.core.dao.DaoException;
import org.wikibrain.core.dao.LocalPageDao;
import org.wikibrain.core.dao.RawImageDao;
import org.wikibrain.core.lang.Language;
import org.wikibrain.core.model.LocalLink;
import org.wikibrain.core.model.LocalPage;
import org.wikibrain.core.model.RawImage;
import org.wikibrain.sr.SRMetric;
import org.wikibrain.sr.SRResult;
import org.wikibrain.sr.SRResultList;
import org.wikibrain.sr.wikify.Wikifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by Josh on 4/6/16.
 */
public class CompariFactWikipediaImages implements CompariFactDataSource {
    final private RawImageDao riDao;
    final private LocalPageDao lpDao;
    final private Map<String, SRMetric> srMetrics;
    final private Wikifier wikifier;

    CompariFactWikipediaImages(Env env) throws ConfigurationException {
        Configurator conf = env.getConfigurator();
        riDao = conf.get(RawImageDao.class);
        lpDao = conf.get(LocalPageDao.class);
        srMetrics = new HashMap<String, SRMetric>();
        Language lang = env.getDefaultLanguage();

        srMetrics.put("esa", conf.get(SRMetric.class, "ESA", "language", lang.getLangCode()));
        srMetrics.put("ensemble", conf.get(SRMetric.class, "ensemble", "language", lang.getLangCode()));
        wikifier = conf.get(Wikifier.class, "websail", "language", lang.getLangCode());
    }

    private List<InternalImage> createImageFromId(Language lang, int localId, String method, double score) throws DaoException {
        return createImageFromId(lang, localId, method, score, "");
    }
    private List<InternalImage> createImageFromId(Language lang, int localId, String method, double score, String debugString) throws DaoException {
        List<InternalImage> images = new ArrayList<InternalImage>();

        for (RawImage image : riDao.getImages(lang, localId)) {
            LocalPage lp = lpDao.getById(lang, localId);
            images.add(new InternalImage(image.getLanguage(), image.getSourceId(), image.getName(),
                    image.getPageLocation(), image.getImageLocation(), image.getCaption(), method, score,
                    lp.getTitle().getCanonicalTitle()));
            images.get(images.size() - 1).debugString = debugString;
        }

        return images;
    }

    private List<InternalImage> srImages(String text, int count, String method) throws DaoException {
        List<InternalImage> result = new ArrayList<InternalImage>();

        SRMetric sr = srMetrics.get(method);
        SRResultList mostSimilar = sr.mostSimilar(text, count);
        for (int i = 0; i < mostSimilar.numDocs(); i++) {
            int id = mostSimilar.getId(i);
            double score = mostSimilar.getScoreForId(id);
            result.addAll(createImageFromId(sr.getLanguage(), id, method, score));
        }

        return result;
    }

    private class ScoredLink {
        public LocalLink link;
        public double score;
    }

    private List<ScoredLink> wikifyText(String text) throws DaoException{
        Map<LocalLink, Double> values = new HashMap<LocalLink, Double>();
        for (LocalLink ll : wikifier.wikify(text)) {
            Double value = 1.0 - (double) ll.getLocation() / (double) text.length();
            if (values.containsKey(ll)) {
                value += values.get(ll);
            }

            values.put(ll, value);
        }

        List<ScoredLink> result = new ArrayList<ScoredLink>();
        for (LocalLink l : values.keySet()) {
            ScoredLink scoredLink = new ScoredLink();
            scoredLink.link = l;
            scoredLink.score = values.get(l);
            result.add(scoredLink);
        }
        return result;
    }

    public List<InternalImage> generateimages(String text, String method) throws DaoException {
        List<InternalImage> result = new ArrayList<InternalImage>();

        if (method.equals("esa") || method.equals("ensemble")) {
            result = srImages(text, 50, method);
        } else if (method.startsWith("wikify")) {
            int index = method.lastIndexOf("-");
            String srMethod = null;
            if (index >= 0) {
                srMethod = method.substring(index + 1);
            }

            for (ScoredLink scoredLink : wikifyText(text)) {
                LocalLink ll = scoredLink.link;
                double score = scoredLink.score;

                result.addAll(createImageFromId(ll.getLanguage(), ll.getLocalId(), "wikify", score, ll.getAnchorText()));

                if (srMethod == null) {
                    continue;
                }

                int numberOfImages = (int)(50.0 * score);
                String title = lpDao.getById(ll.getLanguage(), ll.getLocalId()).getTitle().getCanonicalTitle();
                for (InternalImage image : srImages(title, numberOfImages, srMethod)) {
                    // Change the method from the SR method to the wikify-sr method
                    InternalImage newImage = new InternalImage(image.getLanguage(), image.getSourceId(), image.getName(),
                            image.getPageLocation(), image.getImageLocation(), image.getCaption(), method,
                            image.getScore(), image.getTitle());
                    newImage.debugString = ll.getAnchorText();
                    result.add(newImage);
                }
            }
        }

        return result;
    }
}
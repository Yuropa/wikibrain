package org.wikibrain.webapi;

import cern.jet.random.StudentT;
import edu.emory.mathcs.backport.java.util.Collections;
import org.apache.commons.codec.language.bm.Lang;
import org.apache.commons.collections.ListUtils;
import org.jooq.util.derby.sys.Sys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import org.wikibrain.utils.ParallelForEach;
import org.wikibrain.utils.Procedure;
import org.wikibrain.utils.WpThreadUtils;

import java.lang.reflect.Array;
import java.util.*;

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

    private Map<Integer, List<InternalImage>> createImagesFromIds(Language lang, List<Integer> localIds, String method, List<Double> scores, List<String> debugStrings) throws  DaoException {
        Map<Integer, List<InternalImage>> result = new HashMap<Integer, List<InternalImage>>();
        Map<Integer, String> titleMap = new HashMap<Integer, String>();
        Map<Integer, Double> scoreMap = new HashMap<Integer, Double>();
        Map<Integer, String> debugMap = new HashMap<Integer, String>();

        String allTitles = "";
        int imageCount = 0;

        for (int localId : localIds) {
            LocalPage lp = lpDao.getById(lang, localId);
            titleMap.put(localId, lp.getTitle().getCanonicalTitle());
            allTitles += lp.getTitle().getCanonicalTitle() + ", ";
        }

        for (int i = 0; i < localIds.size(); i++) {
            scoreMap.put(localIds.get(i), scores.get(i));
            debugMap.put(localIds.get(i), debugStrings.get(i));
        }

        for (List<RawImage> images : riDao.getImages(lang, localIds).values()) {
            for (RawImage image : images) {
                int localId = image.getSourceId();
                String title = titleMap.get(localId);
                double score = scoreMap.get(localId);

                InternalImage i = new InternalImage(image.getLanguage(), image.getSourceId(), image.getName(),
                        image.getPageLocation(), image.getImageLocation(), image.getCaption(), image.isPhotograph(),
                        image.getWidth(), image.getHeight(), method, score, title);
                i.debugString = debugMap.get(localId) + " (isPhoto=" + image.isPhotograph() + ")";

                if (!result.containsKey(localId)) {
                    result.put(localId, new ArrayList<InternalImage>());
                }

                result.get(localId).add(i);
                imageCount++;
            }
        }

        System.out.println("Found pages " + allTitles + " with " + imageCount + " images");

        return result;
    }

    private List<InternalImage> combinedImagesFromIds(Language lang, List<Integer> localIds, String method, List<Double> scores, List<String> debugStrings) throws  DaoException {
        List<InternalImage> result = new ArrayList<InternalImage>();
        for (List<InternalImage> images : createImagesFromIds(lang, localIds, method, scores, debugStrings).values()) {
            result.addAll(images);
        }
        return result;
    }

    private List<InternalImage> combinedImagesFromIds(Language lang, List<Integer> localIds, String method, List<Double> scores) throws  DaoException {
        List<String> debugStrings = new ArrayList<String>();
        for (int i = 0; i < localIds.size(); i++) {
            debugStrings.add("");
        }
        return combinedImagesFromIds(lang, localIds, method, scores, debugStrings);
    }

    private List<InternalImage> srImages(String text, int count, final String method) throws DaoException {
        List<InternalImage> result = Collections.synchronizedList(new ArrayList<InternalImage>());

        if (count <= 0) {
            return result;
        }

        SRMetric sr = srMetrics.get(method);
        SRResultList mostSimilar = sr.mostSimilar(text, count);
        List<Integer> ids = new ArrayList<Integer>();
        List<Double> scores = new ArrayList<Double>();

        for (int i = 0; i < mostSimilar.numDocs(); i++) {
            int id = mostSimilar.getId(i);
            double score = mostSimilar.getScoreForId(id);

            ids.add(id);
            scores.add(score);
        }

        result.addAll(combinedImagesFromIds(sr.getLanguage(), ids, method, scores));

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

    public List<InternalImage> generateimages(String text, final String method) throws DaoException {
        final List<InternalImage> result = Collections.synchronizedList(new ArrayList<InternalImage>());
        System.out.println("Generating Wikipedia Images");
        System.out.println("Using method " + method);

        if (method.equals("esa") || method.equals("ensemble")) {
            return srImages(text, 50, method);
        } else if (method.startsWith("wikify")) {
            int index = method.lastIndexOf("-");
            final String srMethod;
            if (index >= 0) {
                srMethod = method.substring(index + 1);
            } else {
                srMethod = null;
            }

            List<Integer> ids = new ArrayList<Integer>();
            List<Double> scores = new ArrayList<Double>();
            List<String> debugStrings = new ArrayList<String>();
            Language lang = srMetrics.values().iterator().next().getLanguage();

            for (ScoredLink scoredLink : wikifyText(text)) {
                int id = scoredLink.link.getLocalId();
                ids.add(id);
                double score = scoredLink.score;
                scores.add(score);
                String debugString = scoredLink.link.getAnchorText();
                debugStrings.add(debugString);

                if (srMethod == null) {
                    continue;
                }

                // Perform ESA if necessary
                int numberOfImages = (int)(50.0 * score);
                String title = lpDao.getById(lang, id).getTitle().getCanonicalTitle();

                for (InternalImage image : srImages(title, numberOfImages, srMethod)) {
                    image.debugString = debugString + "  " + image.debugString;
                }
            }

            result.addAll(combinedImagesFromIds(lang, ids, "wikify", scores, debugStrings));
        }

        System.out.println("Generated " + result.size() + " Wikipedia Images");

        return result;
    }
}

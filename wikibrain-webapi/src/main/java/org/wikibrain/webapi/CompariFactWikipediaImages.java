package org.wikibrain.webapi;

import cern.jet.random.StudentT;
import com.mchange.v2.lang.ThreadUtils;
import edu.emory.mathcs.backport.java.util.Collections;
import org.apache.commons.lang.exception.ExceptionUtils;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by Josh on 4/6/16.
 */
public class CompariFactWikipediaImages implements CompariFactDataSource {
    public static final Logger LOG = LoggerFactory.getLogger(CompariFactWikipediaImages.class);
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

        LocalPage lp = lpDao.getById(lang, localId);

        for (RawImage image : riDao.getImages(lang, localId)) {
            images.add(new InternalImage(image.getLanguage(), image.getSourceId(), image.getName(),
                    image.getPageLocation(), image.getImageLocation(), image.getCaption(), image.isPhotograph(),
                    image.getWidth(), image.getHeight(), method, score, lp.getTitle().getCanonicalTitle()));
        }

        System.out.println("Found page " + lp.getTitle().getCanonicalTitle() + " with " + images.size() + " images");

        return images;
    }

    private List<InternalImage> srImages(String text, int count, final String method) throws DaoException {
        final List<InternalImage> result = Collections.synchronizedList(new ArrayList<InternalImage>());

        if (count <= 0) {
            // No need to search
            return result;
        }

        final SRMetric sr = srMetrics.get(method);
        final SRResultList mostSimilar = sr.mostSimilar(text, count);

        ParallelForEach.range(0, mostSimilar.numDocs(), WpThreadUtils.getMaxThreads() * 4, new Procedure<Integer>() {
            @Override
            public void call(Integer i) throws Exception {
                try {
                    int id = mostSimilar.getId(i);
                    double score = mostSimilar.getScoreForId(id);
                    result.addAll(createImageFromId(sr.getLanguage(), id, method, score));
                } catch (Exception e) {
                    LOG.error(e.getLocalizedMessage());
                    LOG.error(ExceptionUtils.getFullStackTrace(e));
                }
            }
        });

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

            ParallelForEach.loop(wikifyText(text), WpThreadUtils.getMaxThreads() * 4, new Procedure<ScoredLink>() {
                @Override
                public void call(ScoredLink scoredLink) throws Exception {
                    try {
                        LocalLink ll = scoredLink.link;
                        double score = scoredLink.score;

                        result.addAll(createImageFromId(ll.getLanguage(), ll.getLocalId(), "wikify", score, ll.getAnchorText()));

                        if (srMethod == null) {
                            return;
                        }

                        int numberOfImages = (int) (50.0 * score);
                        String title = lpDao.getById(ll.getLanguage(), ll.getLocalId()).getTitle().getCanonicalTitle();
                        for (InternalImage image : srImages(title, numberOfImages, srMethod)) {
                            // Change the method from the SR method to the wikify-sr method
                            InternalImage newImage = new InternalImage(image.getLanguage(), image.getSourceId(), image.getName(),
                                    image.getPageLocation(), image.getImageLocation(), image.getCaption(), image.isPhotograph(),
                                    image.getWidth(), image.getHeight(), method, image.getScore(), image.getTitle());
                            newImage.debugString = ll.getAnchorText();
                            result.add(newImage);
                        }
                    }  catch (Exception e) {
                        LOG.error(e.getLocalizedMessage());
                        LOG.error(ExceptionUtils.getFullStackTrace(e));
                    }
                }
            });
        } else if (method.equals("all")) {
            // This is a combination os ESA and Wikify-ESA
            result.addAll(generateimages(text, "esa"));
            result.addAll(generateimages(text, "wikify-esa"));
        }

        System.out.println("Generated " + result.size() + " Wikipedia Images");

        return result;
    }
}
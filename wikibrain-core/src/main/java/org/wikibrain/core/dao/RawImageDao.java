package org.wikibrain.core.dao;

/**
 * Created by Josh on 3/30/16.
 */

import org.wikibrain.core.lang.Language;
import org.wikibrain.core.model.LocalPage;
import org.wikibrain.core.model.RawImage;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

public interface RawImageDao {
    public RawImage getImage(String title) throws DaoException;
    public Iterator<RawImage> getImages(String startingTitle, String endingTitle) throws DaoException;

    public Iterator<LocalPage> pagesWithImage(RawImage image) throws DaoException;

    public Iterable<RawImage> getImages(Language language, int localId) throws DaoException;
    public Map<Integer, List<RawImage>> getImages(Language language, List<Integer> localIds) throws DaoException;
}

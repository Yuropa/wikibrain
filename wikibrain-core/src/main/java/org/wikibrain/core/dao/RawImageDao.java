package org.wikibrain.core.dao;

/**
 * Created by Josh on 3/30/16.
 */

import org.wikibrain.core.lang.Language;
import org.wikibrain.core.model.RawImage;

import java.util.List;
import java.util.Map;

public interface RawImageDao {
    public Iterable<RawImage> getImages(Language language, int localId) throws DaoException;
    public Map<Integer, List<RawImage>> getImages(Language language, List<Integer> localIds) throws DaoException;
}

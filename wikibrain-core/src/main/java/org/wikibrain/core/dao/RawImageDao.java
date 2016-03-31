package org.wikibrain.core.dao;

/**
 * Created by Josh on 3/30/16.
 */

import org.wikibrain.core.lang.Language;
import org.wikibrain.core.model.RawImage;

public interface RawImageDao {
    public Iterable<RawImage> getImages(Language language, int localId) throws DaoException;
}

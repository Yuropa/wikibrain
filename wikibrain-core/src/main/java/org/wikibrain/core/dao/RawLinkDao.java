package org.wikibrain.core.dao;

import org.wikibrain.core.lang.Language;
import org.wikibrain.core.lang.LocalId;
import org.wikibrain.core.model.LocalLink;
import org.wikibrain.core.model.RawLink;

/**
 * Created by Josh on 3/30/16.
 */
public interface RawLinkDao extends Dao<RawLink> {
    public Iterable<RawLink> getLinks(Language language, int localId) throws DaoException;
}

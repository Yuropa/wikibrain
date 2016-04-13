package org.wikibrain.webapi;

        import org.wikibrain.core.dao.DaoException;

        import java.util.List;

/**
 * Created by Josh on 4/6/16.
 */
interface CompariFactDataSource {
    List<InternalImage> generateimages(String text, String method) throws DaoException;
}

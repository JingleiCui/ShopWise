package com.shopwise.discovery;

import java.util.List;

interface CatalogProvider {
    String name();
    boolean enabled();
    List<Offer> search(String query) throws Exception;
    List<Offer> lookup(String id) throws Exception;
    List<Offer> details(String id) throws Exception;
}

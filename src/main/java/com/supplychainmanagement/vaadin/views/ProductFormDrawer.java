package com.supplychainmanagement.vaadin.views;

import com.supplychainmanagement.entity.Product;
import com.vaadin.flow.component.Composite;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.data.binder.Binder;
import com.vaadin.flow.theme.lumo.LumoUtility;
import com.vaadin.flow.component.orderedlayout.Scroller;
import org.jspecify.annotations.Nullable;

public class ProductFormDrawer extends Composite<VerticalLayout> {

    private final ProductForm form;

    public ProductFormDrawer() {
        this.form = new ProductForm();
        var header = new H2("Product Details");

        var layout = getContent();
        layout.add(header);
        layout.add(new Scroller(form));
        layout.setWidth("300px");
        addClassName(LumoUtility.BoxShadow.MEDIUM);
    }

    public void setProductDetails(@Nullable Product productDetails) {
        form.setFormDataObject(productDetails);
    }
}

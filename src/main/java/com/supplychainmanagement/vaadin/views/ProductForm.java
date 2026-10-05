package com.supplychainmanagement.vaadin.views;

import com.supplychainmanagement.entity.Product;
import com.vaadin.flow.component.Composite;
import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.textfield.BigDecimalField;
import com.vaadin.flow.component.textfield.NumberField;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.binder.Binder;
import org.jspecify.annotations.Nullable;

class ProductForm extends Composite<FormLayout>  {

    private Binder<Product> binder;

    ProductForm() {
//        this.binder = binder;
        // Create components
        var nameField = new TextField("Name");
        var descriptionField = new TextArea("Description");
//        var categoryField = new TextField("Category");
//        var brandField = new TextField("Brand");
        var articleNo = new NumberField("articleNo");
//        var releaseDateField = new DatePicker("Release Date");
        var priceField = new BigDecimalField("Price");
//        var discountField = new BigDecimalField("Discount");
        var releaseDateField = new DatePicker("Release Date");

        // Layout form
        var layout = getContent();
        layout.add(nameField);
        layout.add(descriptionField);
//        layout.add(categoryField);
//        layout.add(brandField);
        layout.add(articleNo);
//        layout.add(releaseDateField);
        layout.add(priceField);
//        layout.add(discountField);
        layout.add(releaseDateField);

        // Bind fields
        binder = new Binder<>();
        binder.forField(nameField)
                .bind(Product::getName,
                        Product::setName);
        binder.forField(descriptionField)
                .bind(Product::getDescription,
                        Product::setDescription);
        binder.forField(articleNo)
                .bind(product -> product.getArticleNo() != null ? product.getArticleNo().doubleValue() : null,
                        (product, value) -> product.setArticleNo(value != null ? value.longValue() : null));
        binder.forField(releaseDateField)
                .bind(product -> product.getCreatedAt() != null ? product.getCreatedAt().toLocalDate() : null,
                        (product, value) -> product.setCreatedAt(value != null ? value.atStartOfDay() : null));
        binder.forField(priceField)
                .bind(Product::getUnitPrice,
                        Product::setUnitPrice);
        binder.setReadOnly(true);
    }

    public void setFormDataObject(@Nullable Product productDetails) {
        binder.setBean(productDetails);
    }
}
package com.supplychainmanagement.vaadin.views;

import com.supplychainmanagement.entity.Component;
import com.vaadin.flow.component.Composite;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.textfield.BigDecimalField;
import com.vaadin.flow.component.textfield.NumberField;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.binder.Binder;
import org.jspecify.annotations.Nullable;

class ComponentForm extends Composite<FormLayout> {

    private final Binder<Component> binder;

    ComponentForm() {
        // Create components
        var nameField = new TextField("Name");
        var manufacturerField = new TextField("Manufacturer");
        var descriptionField = new TextArea("Description");
        var externalIdField = new TextField("External ID");
        var weightField = new BigDecimalField("Weight");

        // Layout form
        var layout = getContent();
        layout.add(nameField);
        layout.add(manufacturerField);
        layout.add(descriptionField);
        layout.add(externalIdField);
        layout.add(weightField);

        // Bind fields
        binder = new Binder<>();
        binder.forField(nameField)
                .bind(Component::getName,
                        Component::setName);
        binder.forField(manufacturerField)
                .bind(Component::getManufacturer,
                        Component::setManufacturer);
        binder.forField(descriptionField)
                .bind(Component::getDescription,
                        Component::setDescription);
        binder.forField(externalIdField)
                .bind(Component::getExternalId,
                        Component::setExternalId);
        binder.forField(weightField)
                .bind(Component::getWeight,
                        Component::setWeight);
        binder.setReadOnly(true);
    }

    public void setFormDataObject(@Nullable Component componentDetails) {
        binder.setBean(componentDetails);
    }
}
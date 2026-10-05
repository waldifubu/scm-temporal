package com.supplychainmanagement.vaadin.views;

import com.supplychainmanagement.entity.Component;
import com.vaadin.flow.component.Composite;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.theme.lumo.LumoUtility;
import com.vaadin.flow.component.orderedlayout.Scroller;
import org.jspecify.annotations.Nullable;

public class ComponentFormDrawer extends Composite<VerticalLayout> {

    private final ComponentForm form;

    public ComponentFormDrawer() {
        this.form = new ComponentForm();
        var header = new H2("Component Details");

        var layout = getContent();
        layout.add(header);
        layout.add(new Scroller(form));
        layout.setWidth("300px");
        addClassName(LumoUtility.BoxShadow.MEDIUM);
    }

    public void setComponentDetails(@Nullable Component componentDetails) {
        form.setFormDataObject(componentDetails);
    }
}
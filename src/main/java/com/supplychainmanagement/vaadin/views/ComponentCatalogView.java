package com.supplychainmanagement.vaadin.views;

import com.supplychainmanagement.entity.Component;
import com.supplychainmanagement.repository.ComponentCatalogRepository;
import com.supplychainmanagement.repository.ComponentDetailsRepository;
import com.supplychainmanagement.repository.ComponentRepository;
import com.vaadin.flow.component.grid.ColumnTextAlign;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;

@Route("components")
@PermitAll
public class ComponentCatalogView extends HorizontalLayout {

    private final ComponentRepository repository;
    private final ComponentCatalogRepository catalogRepository;
    private final ComponentDetailsRepository detailsRepository;

    public ComponentCatalogView(ComponentRepository repository, ComponentCatalogRepository catalogRepository,
                                 ComponentDetailsRepository detailsRepository) {
        this.repository = repository;
        this.catalogRepository = catalogRepository;
        this.detailsRepository = detailsRepository;

        // Create components
        var searchField = new TextField();
        searchField.setPlaceholder("Search");
        searchField.setPrefixComponent(VaadinIcon.SEARCH.create());
        searchField.setValueChangeMode(ValueChangeMode.LAZY);

        var grid = new Grid<Component>();
        grid.addColumn(Component::getId).setHeader("ID").setSortProperty("id").setAutoWidth(true);
        grid.addColumn(Component::getName).setHeader("Name").setSortProperty("name");
        grid.addColumn(Component::getManufacturer).setHeader("Manufacturer").setSortProperty("manufacturer");
        grid.addColumn(Component::getArticleNo).setHeader("Article No").setTextAlign(ColumnTextAlign.END).setSortProperty("articleNo");
        grid.addColumn(Component::getDescription).setHeader("Description");
        grid.addColumn(Component::getSku).setHeader("SKU").setSortProperty("sku");
        grid.addColumn(Component::getWeight).setHeader("Weight").setTextAlign(ColumnTextAlign.END).setSortProperty("weight");
        grid.addColumn(component -> component.getProduct().getName()).setHeader("Product").setSortProperty("product");

        grid.setItemsPageable(pageable -> catalogRepository
                .findByNameContainingIgnoreCase(searchField.getValue(), pageable)
                .getContent()
        );

        var drawer = new ComponentFormDrawer();

        searchField.addValueChangeListener(e -> grid.getDataProvider().refreshAll());

        grid.addSelectionListener(e -> {
            var componentDetails = e.getFirstSelectedItem()
                    .flatMap(item -> detailsRepository
                            .findById(item.getId()))
                    .orElse(null);
            drawer.setComponentDetails(componentDetails);
        });

        // Layout view
        setSizeFull();
        setSpacing(false);

        var listLayout = new VerticalLayout(searchField, grid);
        listLayout.setSizeFull();
        grid.setSizeFull();

        add(listLayout, drawer);
        setFlexShrink(0, drawer);
    }
}
package com.supplychainmanagement.vaadin.views;

import com.supplychainmanagement.entity.Product;
import com.supplychainmanagement.repository.ProductCatalogRepository;
import com.supplychainmanagement.repository.ProductDetailsRepository;
import com.supplychainmanagement.repository.ProductRepository;
import com.vaadin.flow.component.grid.ColumnTextAlign;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;

@Route("products")
@PermitAll
public class ProductCatalogView extends HorizontalLayout {

    private final ProductRepository repository;
    private final ProductCatalogRepository catalogRepository;
    private final ProductDetailsRepository productDetailsRepository;

    public ProductCatalogView(ProductRepository repository, ProductCatalogRepository catalogRepository,
                              ProductDetailsRepository productDetailsRepository) {
        this.repository = repository;
        this.catalogRepository = catalogRepository;
        this.productDetailsRepository = productDetailsRepository;
        // Add components and layout for the product catalog view
        // Create components

        // Create components
        var searchField = new TextField();
        searchField.setPlaceholder("Search");
        searchField.setPrefixComponent(VaadinIcon.SEARCH.create());
        searchField.setValueChangeMode(ValueChangeMode.LAZY);

        var grid = new Grid<Product>();
        grid.addColumn(Product::getId).setHeader("ID").setSortProperty("id").setAutoWidth(true);
        grid.addColumn(Product::getName).setHeader("Name").setSortProperty("name");
        grid.addColumn(Product::getArticleNo).setHeader("Article No").setTextAlign(ColumnTextAlign.END).setSortProperty("articleNo");
        grid.addColumn(Product::getDescription).setHeader("Description");

        grid.addColumn(Product::getSku).setHeader("SKU").setSortProperty("sku");
        grid.addColumn(Product::getUnitPrice).setHeader("Unit Price").setTextAlign(ColumnTextAlign.END).setSortProperty("unitPrice");

        grid.setItemsPageable(pageable -> catalogRepository
                .findByNameContainingIgnoreCase(searchField.getValue(), pageable)
                .getContent()
        );

        var drawer = new ProductFormDrawer();

        searchField.addValueChangeListener(e -> grid.getDataProvider().refreshAll());

        grid.addSelectionListener(e -> {
            var productDetails = e.getFirstSelectedItem()
                    .flatMap(item -> productDetailsRepository
                            .findById(item.getId()))
                    .orElse(null);
            drawer.setProductDetails(productDetails);
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

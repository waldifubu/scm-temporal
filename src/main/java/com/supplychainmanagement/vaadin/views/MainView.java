package com.supplychainmanagement.vaadin.views;

import com.vaadin.flow.component.Text;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.Menu;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;

@Route("/")
@PageTitle("Main View lal la")
@Menu(order = 0, icon = "icons/clipboard-check.svg", title = "Task List")
@AnonymousAllowed
public class MainView extends VerticalLayout {

    public MainView() {
        add(new Text("This is the main view of the Supply Chain Management application."));
        add(new Paragraph("Here you can manage your supply chain operations, track shipments, and monitor inventory levels."));
        setPadding(true);
        add(new Text("Welcome to MainView."));
    }
}

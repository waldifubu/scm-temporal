package com.supplychainmanagement.service;

import com.supplychainmanagement.dto.shipping.PackageItemResponse;
import com.supplychainmanagement.entity.PackageItem;
import com.supplychainmanagement.repository.PackageItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Builds PackageItemResponses, siblings included. The siblings are stored nowhere: they are the
 * other package items of the same order line - loose or in any package - looked up when the
 * response is built.
 * <p>
 * A component rather than a static mapper because it needs the database, and one query per call
 * rather than per item: a page of 25 items costs one extra query, not 25. Call it while the
 * session is open - it reads each item's order line.
 */
@Component
@RequiredArgsConstructor
public class PackageItemResponseAssembler {

    private final PackageItemRepository packageItemRepository;

    public List<PackageItemResponse> toResponses(Collection<PackageItem> items) {
        Map<Long, List<Long>> itemIdsByLine = itemIdsByLine(items);
        return items.stream().map(item -> toResponse(item, itemIdsByLine)).toList();
    }

    public Page<PackageItemResponse> toResponses(Page<PackageItem> page) {
        Map<Long, List<Long>> itemIdsByLine = itemIdsByLine(page.getContent());
        return page.map(item -> toResponse(item, itemIdsByLine));
    }

    public PackageItemResponse toResponse(PackageItem item) {
        return toResponses(List.of(item)).getFirst();
    }

    /** All package item ids of the lines at hand, per line, in ascending id order. */
    private Map<Long, List<Long>> itemIdsByLine(Collection<PackageItem> items) {
        Set<Long> orderItemIds = items.stream()
                .map(item -> item.getOrderItem().getId())
                .collect(Collectors.toCollection(TreeSet::new));
        if (orderItemIds.isEmpty()) {
            // Nothing to look up - and an empty IN would not be valid SQL.
            return Map.of();
        }

        return packageItemRepository.findItemIdsByOrderItemIdIn(orderItemIds).stream()
                .collect(Collectors.groupingBy(PackageItemRepository.ItemOfLine::getOrderItemId,
                        Collectors.mapping(PackageItemRepository.ItemOfLine::getPackageItemId, Collectors.toList())));
    }

    private static PackageItemResponse toResponse(PackageItem item, Map<Long, List<Long>> itemIdsByLine) {
        List<Long> siblings = itemIdsByLine.getOrDefault(item.getOrderItem().getId(), List.of()).stream()
                .filter(id -> !Objects.equals(id, item.getId()))
                .toList();
        return PackageItemResponse.from(item, siblings);
    }
}

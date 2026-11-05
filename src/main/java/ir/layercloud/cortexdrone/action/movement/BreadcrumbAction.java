package ir.layercloud.cortexdrone.action.movement;

import ir.layercloud.cortexdrone.model.DroneController;
import java.util.ArrayList;
import java.util.List;

/** Read-only breadcrumb calculation. */
public class BreadcrumbAction implements MovementAction {
    @Override
    public String operation() { return "get_breadcrumbs"; }

    @Override
    public MovementResult execute(MovementRequest request) {
        DroneController ctrl = request.controller();
        if (ctrl == null) return MovementResult.failure("No active drone");
        int limit = Math.min(256, Math.max(4, request.limit()));
        List<int[]> hist = new ArrayList<int[]>(ctrl.navigationHistory);
        int start = Math.max(0, hist.size() - limit);
        List<int[]> page = new ArrayList<int[]>();
        for (int i = start; i < hist.size(); i++) page.add(hist.get(i));
        return MovementResult.breadcrumbs(page, ctrl.lastSurfaceAnchor, hist.size());
    }
}

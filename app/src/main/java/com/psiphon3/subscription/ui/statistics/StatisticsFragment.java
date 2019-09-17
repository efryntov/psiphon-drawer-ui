package com.psiphon3.subscription.ui.statistics;

import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.psiphon3.DataTransferStats;
import com.psiphon3.TunnelServiceInteractor;
import com.psiphon3.Utils;

import org.achartengine.ChartFactory;
import org.achartengine.GraphicalView;
import org.achartengine.model.XYMultipleSeriesDataset;
import org.achartengine.model.XYSeries;
import org.achartengine.renderer.XYMultipleSeriesRenderer;
import org.achartengine.renderer.XYSeriesRenderer;

import java.util.ArrayList;

import com.psiphon3.subscription.R;

public class StatisticsFragment extends Fragment {
    private TunnelServiceInteractor tunnelServiceInteractor;

    private TextView elapsedConnectionTimeView;
    private TextView totalSentView;
    private TextView totalReceivedView;
    private DataTransferGraph slowSentGraph;
    private DataTransferGraph slowReceivedGraph;
    private DataTransferGraph fastSentGraph;
    private DataTransferGraph fastReceivedGraph;

    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_statistics, container, false);
        slowSentGraph = new DataTransferGraph(root.findViewById( R.id.slowSentGraph));
        slowReceivedGraph = new DataTransferGraph(root.findViewById(R.id.slowReceivedGraph));
        fastSentGraph = new DataTransferGraph(root.findViewById(R.id.fastSentGraph));
        fastReceivedGraph = new DataTransferGraph(root.findViewById(R.id.fastReceivedGraph));
        elapsedConnectionTimeView = (TextView) root.findViewById(R.id.elapsedConnectionTime);
        totalSentView = (TextView) root.findViewById(R.id.totalSent);
        totalReceivedView = (TextView) root.findViewById(R.id.totalReceived);


        tunnelServiceInteractor = new TunnelServiceInteractor(getContext());
        tunnelServiceInteractor.dataStatsFlowable()
                .startWith(Boolean.FALSE)
                .doOnNext(this::updateStatisticsUICallback)
                .subscribe();

        return root;
    }

    private void updateStatisticsUICallback(boolean isConnected) {
        DataTransferStats.DataTransferStatsForUI dataTransferStats = DataTransferStats.getDataTransferStatsForUI();
        elapsedConnectionTimeView.setText(isConnected ? getString(R.string.connected_elapsed_time,
                Utils.elapsedTimeToDisplay(dataTransferStats.getElapsedTime())) : getString(R.string.disconnected));
        totalSentView.setText(Utils.byteCountToDisplaySize(dataTransferStats.getTotalBytesSent(), false));
        totalReceivedView.setText(Utils.byteCountToDisplaySize(dataTransferStats.getTotalBytesReceived(), false));
        slowSentGraph.update(dataTransferStats.getSlowSentSeries());
        slowReceivedGraph.update(dataTransferStats.getSlowReceivedSeries());
        fastSentGraph.update(dataTransferStats.getFastSentSeries());
        fastReceivedGraph.update(dataTransferStats.getFastReceivedSeries());
    }

    @Override
    public void onResume() {
        super.onResume();
        tunnelServiceInteractor.resume(getContext());
    }

    @Override
    public void onPause() {
        super.onPause();
        tunnelServiceInteractor.pause(getContext());
    }

    private class DataTransferGraph {
        private final LinearLayout graphLayout;
        private GraphicalView chartView;
        private final XYMultipleSeriesDataset chartDataset;
        private final XYMultipleSeriesRenderer chartRenderer;
        private final XYSeries chartCurrentSeries;

        DataTransferGraph(LinearLayout layout) {
            graphLayout = layout;
            chartDataset = new XYMultipleSeriesDataset();
            chartRenderer = new XYMultipleSeriesRenderer();
            chartRenderer.setGridColor(Color.GRAY);
            chartRenderer.setShowGrid(true);
            chartRenderer.setShowLabels(false);
            chartRenderer.setShowLegend(false);
            chartRenderer.setShowAxes(false);
            chartRenderer.setPanEnabled(false, false);
            chartRenderer.setZoomEnabled(false, false);

            // Make the margins transparent.
            // Note that this value is a bit magical. One would expect
            // android.graphics.Color.TRANSPARENT to work, but it doesn't.
            // Nor does 0x00000000. Ref:
            // http://developer.android.com/reference/android/graphics/Color.html
            chartRenderer.setMarginsColor(0x00FFFFFF);

            chartCurrentSeries = new XYSeries("");
            chartDataset.addSeries(chartCurrentSeries);
            XYSeriesRenderer m_chartCurrentRenderer = new XYSeriesRenderer();
            m_chartCurrentRenderer.setColor(Color.BLUE);
            chartRenderer.addSeriesRenderer(m_chartCurrentRenderer);
        }

        void update(ArrayList<Long> data) {
            chartCurrentSeries.clear();
            for (int i = 0; i < data.size(); i++) {
                chartCurrentSeries.add(i, data.get(i));
            }
            if (chartView == null) {
                chartView = ChartFactory.getLineChartView(getContext(), chartDataset, chartRenderer);
                graphLayout.addView(chartView);
            } else {
                chartView.repaint();
            }
        }
    }
}
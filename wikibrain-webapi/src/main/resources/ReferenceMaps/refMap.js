function generateMap(annotations, mapStyle, center, zoom) {
    var container = $('#visualization-container');
    
    var mapClass = mapStyle.replace('mapbox.', '');
    container.append("<div class='map-container'><div class='fill " + mapClass + "'></div></div>");
    container = container.find('.fill');
    
    var width = container.width();
    var height = container.height();
    
    projection = d3.geo.mercator()
        .center(center)
        .scale(zoom);

    var path = d3.geo.path()
        .projection(projection);
    
    var graph = d3.select(container.get(0)).append("svg:svg")
        .attr("width", width)
        .attr("height", height)
        .append("svg:g")
        .attr("transform", "translate(0,0)");
    
    var tile = d3.geo.tile()
            .size([width, height]);
    var raster = graph.append("g")
            .attr("class", "tile-background");
    
    // Extract all the place that have annotations
    var annotatedPlaces = [];
    for (var i = 0; i < annotations.length; i++) {
        var annotation = annotations[i];

        // Get the location
        var location = [0, 0];
        var coordinates = JSON.parse(annotation.location);

        if (coordinates.hasOwnProperty('lng')) {
            location = projection([coordinates.lng, coordinates.lat]);
        } else {
            var feature = locationsById[annotation.location];
            if (feature == undefined) {
                continue;
            }
            location = path.centroid(feature);
        }

        annotatedPlaces.push({
            title: annotation.title,
            text: annotation.text,
            url: annotation.url,
            location: location
        });
    }

    displayAnnotations(graph, {
        width: width,
        height: height
    }, annotatedPlaces);
    
    function updateTiles() {
        var projectionOrigin = projection([0, 0]);
        
        var tiles = tile
            .scale(projection.scale() * 2 * Math.PI)
            .translate([projectionOrigin[0], projectionOrigin[1]])
            .zoomDelta((window.devicePixelRatio || 1) - .5)();

        var image = raster
            .attr("transform", "scale(" + tiles.scale + ")translate(" + tiles.translate[0] + "," + tiles.translate[1] + ")")
            .selectAll("image")
            .data(tiles, function(d) { return d; });

        image.exit()
            .remove();

        image.enter().append("image")
            .attr("xlink:href", function(d) { 
                return "https://api.mapbox.com/v4/" + mapStyle + "/" + d[2] + "/" + d[0] + "/" + d[1] + ".png?access_token=pk.eyJ1IjoieXVyb3BhIiwiYSI6IktZNTY0SEEifQ.-y0oZrmBr7GEbhejFYdqcA";
            })
            .attr("width", 1)
            .attr("height", 1)
            .attr("x", function(d) { return d[0]; })
            .attr("y", function(d) { return d[1]; });
    }
    
    updateTiles();
}

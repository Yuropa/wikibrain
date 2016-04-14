function displayAnnotations(graph, graph_size, graphAnnotations) {
    /*
        location: [414.3437049980968, 345.03771235502]
        text: "The minimum value is 28.7 at 8025"
        title: "Minimum Value"
        url: ""
    */
    
    var labels = [];
    var anchors = [];
    for (var i = 0; i < graphAnnotations.length; i++) {
        var annotation = graphAnnotations[i];
        var html = annotationHTML(annotation);
        var size = HTMLSize(html);
        
        if (annotation.location == null) {
            continue;
        }
        
        var label = {
            x: annotation.location[0],
            y: annotation.location[1],
            name: html,
            width: size.width,
            height: size.height
        }
        labels.push(label);
        
        var anchor = {
            x: annotation.location[0],
            y: annotation.location[1],
            r: (annotation.text.length > 0 ? 20 : 8)
        }
        anchors.push(anchor);
    }
    
    d3.labeler()
        .label(labels)
        .anchor(anchors)
        .width(Infinity)
        .height(Infinity)
        .start(1000);
    
    // Draw the lines
    graph.append('g')
        .attr("class", "annotationConnectors")
        .attr("transform", "translate(0,0)scale(1)");
    
    // Draw anchors
    graph.append('g')
        .attr("class", "annotationsAnchors")
        .selectAll("circle")
        .data(anchors)
        .enter().append("circle")
        .attr("r", function(d) { return 2; })
        .attr("cx", function(d, i) { 
            return (d.x); 
        })
        .attr("cy", function(d, i) {
            return (d.y);
        });
    
    // Draw labels
    var container = $(graph.node().parentElement.parentElement);
    for (var i = 0; i < labels.length; i++) {
        container.append( labels[i].name);
        var annotation = container.children().last();
        
        annotation.children().last().css({
            position : "relative",
            left: ((labels[i].x) + "px"),
            top:  ((labels[i].y) + "px")
        });
    }
    
    updateConnections();
}

function annotationHTML(annotation) {
    var title = annotation.title;
    var text  = annotation.text;
    // We don't really support urls any more
    var url   = annotation.url;
    
    var titleHTML = "<div contenteditable='false' class='annotationTitle'>";
    /*if (url.length > 0) {
        titleHTML += "<a target='_blank' href='" + url + "'>" + title + "</a>";
    } else {
        titleHTML += title;
    }*/
    
    titleHTML += title;
    titleHTML += "</div>";
    
    var annotationClass = "";
    if (text.length <= 0) {
        annotationClass = 'noTextAnnotation';
    }
        
    var result = "";
    result += "<div class='annotationContainer " + annotationClass + "'>";
    result += titleHTML;
    result += "<div contenteditable='false' class='annotationText'>" + text + "</div>";
    result += "</div>";
    
    result = "<div class='annotationTextContainer' style='position:absolute; top:0px; left:0px; transform: scale(1)'>" + result + "</div>";
    
    return result;
}

function HTMLSize(html) {
    var o = $(html)
            .appendTo($('body'));
    var width  = o.width();
    var height = o.height();
    
    o.remove();

    return {width: width, height, height};
}

function updateConnections() {
    var anchors = $('#visualization-container').find('.annotationsAnchors circle');
    var annotations = $('#visualization-container').find('.annotationTextContainer');
    
    function getAnnotationElement(i) {
        var elm = $(annotations.get(i));
        var child = elm.find('.annotationContainer');
        if (child.length > 0) {
            return child;
        }
        return elm.find('.annotationTitle');
    }
    
    var scale = 1.0;
    
    d3.select('#visualization-container')
        .select('.annotationConnectors')
        .selectAll("line")
        .remove();
        
    d3.select('#visualization-container')
        .select('.annotationConnectors')
        .selectAll("line")
        .data(anchors.toArray())
        .enter().append("line")
        .style("stroke-width", 1.0 / scale + "px")
        .attr("x1", function(d) { return $(d).attr('cx'); })
        .attr("y1", function(d) { return $(d).attr('cy'); })
        .attr("x2", function(d, i) { 
            var elm = getAnnotationElement(i);
            var parent = elm.parent();
        
            return (elm.position().left/scale + (parent.position().left)/scale); 
        })
        .attr("y2", function(d, i) { 
            var elm = getAnnotationElement(i);
            var parent = elm.parent();
            
            return (elm.position().top/scale + (parent.position().top)/scale); 
        });
}